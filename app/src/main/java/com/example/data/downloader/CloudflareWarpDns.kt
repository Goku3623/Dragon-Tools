package com.example.data.downloader

import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class WarpDnsTelemetry(
    val isEnabled: Boolean = false,
    val isAdGuardDnsEnabled: Boolean = false,
    val isUnfilteredAdGuardEnabled: Boolean = false,
    val resolvedHostCount: Int = 0,
    val cacheHitCount: Int = 0,
    val blockedHostCount: Int = 0,
    val lastLatencyMs: Long = 9L,
    val activeResolverLabel: String = "System Default DNS"
)

/**
 * High-speed Cloudflare WARP DNS (1.1.1.1 / 1.0.0.1), AdGuard DNS (94.140.14.14 / 94.140.15.15),
 * & Unfiltered AdGuard DNS (94.140.14.140 / 94.140.14.141) accelerator implementing [okhttp3.Dns]
 * and providing WebView DNS resolution, AdGuard DNS sinkhole filtering, and ISP-block HTTP/HTTPS tunneling.
 */
class CloudflareWarpDns(
    initialEnabled: Boolean = false,
    initialAdGuardDnsEnabled: Boolean = false,
    initialUnfilteredAdGuardEnabled: Boolean = false
) : Dns {

    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAtElapsedMs: Long,
        val isSinkholed: Boolean = false
    )

    internal data class DnsWireResult(
        val addresses: List<InetAddress>,
        val ttlSeconds: Long,
        val isSinkholed: Boolean = false,
        val isTruncated: Boolean = false
    )

    @Volatile
    var isEnabled: Boolean = initialEnabled
        private set

    @Volatile
    var isAdGuardDnsEnabled: Boolean = initialAdGuardDnsEnabled
        private set

    @Volatile
    var isUnfilteredAdGuardEnabled: Boolean = initialUnfilteredAdGuardEnabled
        private set

    val isAnyCustomDnsActive: Boolean
        get() = isEnabled || isAdGuardDnsEnabled || isUnfilteredAdGuardEnabled

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val systemDnsFailedHosts = ConcurrentHashMap<String, Boolean>()
    private val forcedTunnelHosts = ConcurrentHashMap.newKeySet<String>()
    private val inFlightPrewarmHosts = ConcurrentHashMap.newKeySet<String>()
    private val lookupLocks = ConcurrentHashMap<String, Any>()
    private val prewarmExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    private val resolvedCounter = AtomicInteger(0)
    private val cacheHitCounter = AtomicInteger(0)
    private val blockedCounter = AtomicInteger(0)
    private val lastLatencyMsAtomic = AtomicLong(9L)

    // Adaptive UDP & DoH health trackers: avoid stalling WebView threads on blocked networks
    private val udpConsecutiveFailures = AtomicInteger(0)
    @Volatile
    private var udpBypassUntilElapsedMs: Long = 0L
    private val dohConsecutiveFailures = AtomicInteger(0)
    @Volatile
    private var dohBypassUntilElapsedMs: Long = 0L

    private val _telemetryFlow = MutableStateFlow(
        WarpDnsTelemetry(
            isEnabled = initialEnabled,
            isAdGuardDnsEnabled = initialAdGuardDnsEnabled,
            isUnfilteredAdGuardEnabled = initialUnfilteredAdGuardEnabled,
            activeResolverLabel = buildResolverLabel(
                initialEnabled,
                initialAdGuardDnsEnabled,
                initialUnfilteredAdGuardEnabled
            )
        )
    )
    val telemetryFlow: StateFlow<WarpDnsTelemetry> = _telemetryFlow.asStateFlow()

    // Bootstrap client resolves Cloudflare (1.1.1.1/1.0.0.1), AdGuard (94.140.14.14/15.15),
    // and Unfiltered AdGuard (94.140.14.140/141) directly by literal IP bytes so DoH never depends on system DNS.
    private val dohBootstrapClient: OkHttpClient by lazy {
        val staticDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return when (hostname.lowercase(Locale.US)) {
                    PRIMARY_IPV4, "cloudflare-dns.com", "one.one.one.one", WARP_DOT_HOSTNAME -> listOf(
                        InetAddress.getByAddress(hostname, PRIMARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, SECONDARY_IPV4_BYTES)
                    )
                    SECONDARY_IPV4 -> listOf(
                        InetAddress.getByAddress(hostname, SECONDARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, PRIMARY_IPV4_BYTES)
                    )
                    ADGUARD_DEFAULT_PRIMARY_IPV4, ADGUARD_DEFAULT_SECONDARY_IPV4, ADGUARD_DEFAULT_HOST -> listOf(
                        InetAddress.getByAddress(hostname, ADGUARD_DEFAULT_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, ADGUARD_DEFAULT_SECONDARY_BYTES)
                    )
                    ADGUARD_UNFILTERED_PRIMARY_IPV4, ADGUARD_UNFILTERED_SECONDARY_IPV4, ADGUARD_UNFILTERED_HOST -> listOf(
                        InetAddress.getByAddress(hostname, ADGUARD_UNFILTERED_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, ADGUARD_UNFILTERED_SECONDARY_BYTES)
                    )
                    else -> Dns.SYSTEM.lookup(hostname)
                }
            }
        }
        OkHttpClient.Builder()
            .dns(staticDns)
            .connectionPool(ConnectionPool(6, 3, TimeUnit.MINUTES))
            .connectTimeout(900, TimeUnit.MILLISECONDS)
            .readTimeout(1100, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Unfiltered redirect-resolver client (uses Cloudflare 1.1.1.1 DoH + System fallback) for unwrapping
    // HTTP 302 click-tracker links when AdGuard DNS is active so explicit user link taps reach the real destination page.
    private val redirectResolverClient: OkHttpClient by lazy {
        val unfilteredDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val cleanHost = hostname.trim().lowercase(Locale.US).trimEnd('.')
                if (cleanHost.isBlank() || cleanHost == "localhost" || isIpLiteral(cleanHost)) {
                    return Dns.SYSTEM.lookup(hostname)
                }
                val cfDoh = queryCloudflareDoh(cleanHost)?.first
                if (!cfDoh.isNullOrEmpty()) return cfDoh
                val cfWire = queryAdGuardDohWire(cleanHost, PRIMARY_IPV4)?.addresses
                if (!cfWire.isNullOrEmpty()) return cfWire
                return Dns.SYSTEM.lookup(hostname)
            }
        }
        OkHttpClient.Builder()
            .dns(unfilteredDns)
            .connectionPool(ConnectionPool(6, 2, TimeUnit.MINUTES))
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Browser tunnel client using this CloudflareWarpDns resolver for ISP-blocked websites and video CDNs
    private val browserTunnelClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(this)
            .connectionPool(ConnectionPool(20, 4, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (enabled) {
            isAdGuardDnsEnabled = false
            isUnfilteredAdGuardEnabled = false
        }
        resetStateOnModeSwitch()
    }

    fun setAdGuardDnsEnabled(enabled: Boolean) {
        isAdGuardDnsEnabled = enabled
        if (enabled) {
            isEnabled = false
            isUnfilteredAdGuardEnabled = false
        }
        resetStateOnModeSwitch()
    }

    fun setUnfilteredAdGuardEnabled(enabled: Boolean) {
        isUnfilteredAdGuardEnabled = enabled
        if (enabled) {
            isEnabled = false
            isAdGuardDnsEnabled = false
        }
        resetStateOnModeSwitch()
    }

    private fun resetStateOnModeSwitch() {
        dnsCache.clear()
        systemDnsFailedHosts.clear()
        inFlightPrewarmHosts.clear()
        udpConsecutiveFailures.set(0)
        udpBypassUntilElapsedMs = 0L
        dohConsecutiveFailures.set(0)
        dohBypassUntilElapsedMs = 0L
        publishTelemetry()
    }

    fun clearCache() {
        dnsCache.clear()
        systemDnsFailedHosts.clear()
        inFlightPrewarmHosts.clear()
        publishTelemetry()
    }

    fun markHostRequiresTunnel(hostname: String?) {
        val normalized = hostname?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return
        if (normalized.isNotBlank() && normalized != "localhost" && !isIpLiteral(normalized)) {
            forcedTunnelHosts.add(normalized)
            systemDnsFailedHosts[normalized] = true
        }
    }

    /**
     * Checks if a hostname is sinkholed/blocked by AdGuard DNS (`94.140.14.14`).
     * Never blocks when WARP DNS (`1.1.1.1`) or Unfiltered AdGuard DNS (`94.140.14.140`) is active,
     * and never blocks media streaming CDNs.
     */
    fun isHostSinkholedByAdGuard(hostname: String): Boolean {
        if (!isAdGuardDnsEnabled || isEnabled || isUnfilteredAdGuardEnabled) return false
        val normalized = hostname.trim().lowercase(Locale.US).trimEnd('.')
        if (normalized.isBlank() || isMediaStreamingOrEssentialHost(normalized)) return false

        if (isBuiltInAdGuardSinkholeHost(normalized)) {
            val now = SystemClock.elapsedRealtime()
            dnsCache.putIfAbsent(
                normalized,
                CachedDnsEntry(
                    addresses = emptyList(),
                    expiresAtElapsedMs = now + 300_000L,
                    isSinkholed = true
                )
            )
            return true
        }

        val cached = dnsCache[normalized]
        if (cached != null && SystemClock.elapsedRealtime() < cached.expiresAtElapsedMs) {
            return cached.isSinkholed
        }
        return false
    }

    /**
     * Evaluates on WebView's background network thread whether a browser request should be blocked
     * by AdGuard DNS (`94.140.14.14`). Never blocks or stalls video/audio buffering chunks,
     * `Range:` byte requests, HLS/DASH manifests, or essential streaming CDNs.
     */
    fun shouldBlockBrowserRequest(
        requestUrl: String,
        requestHost: String?,
        pageHost: String?,
        isMainFrame: Boolean,
        requestHeaders: Map<String, String>? = null
    ): Boolean {
        if (!isAdGuardDnsEnabled || isEnabled || isUnfilteredAdGuardEnabled) return false
        val host = requestHost?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return false
        if (host.isBlank() || host == "localhost" || isIpLiteral(host)) return false

        // Never block or stall byte-range media buffering, video/audio MIME requests, or streaming assets
        if (hasMediaStreamingHeaders(requestHeaders) ||
            isMediaStreamingOrEssentialHost(host) ||
            isLikelyMediaStreamAssetUrl(requestUrl)
        ) {
            return false
        }

        // Instant match against built-in AdGuard DNS sinkhole rules
        if (isBuiltInAdGuardSinkholeHost(host)) {
            blockedCounter.incrementAndGet()
            publishTelemetry()
            return true
        }

        val now = SystemClock.elapsedRealtime()
        val cached = dnsCache[host]
        if (cached != null && now < cached.expiresAtElapsedMs) {
            if (cached.isSinkholed) {
                blockedCounter.incrementAndGet()
                publishTelemetry()
                return true
            }
            return false
        }

        // For third-party subresources (scripts, frames, ad pixels), check AdGuard DNS without blocking media/HLS fetches.
        if (!isMainFrame && !isSameRegistrableDomain(host, pageHost)) {
            return try {
                lookup(host)
                false
            } catch (_: UnknownHostException) {
                val afterLookup = dnsCache[host]
                if (afterLookup?.isSinkholed == true) {
                    blockedCounter.incrementAndGet()
                    publishTelemetry()
                    true
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }
        return false
    }

    /**
     * Creates a clean, CORS-safe empty [WebResourceResponse] matching the expected resource type
     * so blocked AdGuard DNS subrequests never cause JS ReferenceErrors or broken iframe boxes.
     */
    fun createBlockedWebResourceResponse(
        requestUrl: String,
        acceptHeader: String? = null
    ): WebResourceResponse {
        val pathLower = requestUrl.substringBefore('?').lowercase(Locale.US)
        val acceptLower = acceptHeader?.lowercase(Locale.US).orEmpty()
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Cache-Control" to "no-store"
        )
        return when {
            pathLower.endsWith(".js") || acceptLower.contains("javascript") -> {
                val jsBytes = "/* blocked by AdGuard DNS 94.140.14.14 */".toByteArray(Charsets.UTF_8)
                WebResourceResponse(
                    "application/javascript",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(jsBytes)
                )
            }
            pathLower.endsWith(".css") || acceptLower.contains("text/css") -> {
                WebResourceResponse(
                    "text/css",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(ByteArray(0))
                )
            }
            pathLower.endsWith(".gif") || pathLower.endsWith(".png") ||
                pathLower.endsWith(".jpg") || pathLower.endsWith(".jpeg") ||
                pathLower.endsWith(".webp") || pathLower.endsWith(".svg") ||
                acceptLower.contains("image/") -> {
                WebResourceResponse(
                    "image/gif",
                    null,
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(TRANSPARENT_1X1_GIF)
                )
            }
            pathLower.endsWith(".xml") ||
                requestUrl.contains("vast", ignoreCase = true) ||
                requestUrl.contains("vmap", ignoreCase = true) ||
                requestUrl.contains("gampad", ignoreCase = true) ||
                acceptLower.contains("xml") -> {
                // Return valid empty VAST XML so video players skip pre-roll ads in 0ms and start buffering immediately
                val vastBytes = "<VAST version=\"3.0\"></VAST>".toByteArray(Charsets.UTF_8)
                WebResourceResponse(
                    "application/xml",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(vastBytes)
                )
            }
            pathLower.endsWith(".json") || acceptLower.contains("application/json") -> {
                val jsonBytes = "{}".toByteArray(Charsets.UTF_8)
                WebResourceResponse(
                    "application/json",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(jsonBytes)
                )
            }
            acceptLower.contains("text/html") -> {
                val htmlBytes = "<!DOCTYPE html><html><head><style>body{margin:0;background:transparent;}</style></head><body></body></html>"
                    .toByteArray(Charsets.UTF_8)
                WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(htmlBytes)
                )
            }
            else -> {
                WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(ByteArray(0))
                )
            }
        }
    }

    /**
     * Pre-resolves [hostname] via the active Custom DNS (`1.1.1.1`, `94.140.14.14`, or `94.140.14.140`)
     * and checks in background whether System DNS fails/poisons this host so ISP-blocked domains
     * are seamlessly tunneled.
     */
    fun prewarmHostAsync(hostname: String) {
        if (!isAnyCustomDnsActive) return
        val normalized = hostname.trim().lowercase(Locale.US).trimEnd('.')
        if (normalized.isBlank() || normalized == "localhost" || isIpLiteral(normalized)) return
        val now = SystemClock.elapsedRealtime()
        val cached = dnsCache[normalized]
        if (cached != null && now < cached.expiresAtElapsedMs && systemDnsFailedHosts.containsKey(normalized)) {
            cacheHitCounter.incrementAndGet()
            publishTelemetry()
            return
        }
        if (!inFlightPrewarmHosts.add(normalized)) return
        try {
            prewarmExecutor.execute {
                try {
                    lookup(normalized)
                    checkIfSystemDnsPoisonedOrFailed(normalized)
                } catch (_: Throwable) {
                } finally {
                    inFlightPrewarmHosts.remove(normalized)
                }
            }
        } catch (_: Throwable) {
            inFlightPrewarmHosts.remove(normalized)
        }
    }

    /**
     * Resolves an ad/tracker click-redirect URL asynchronously via HTTP 301/302/303/307/308 `Location`
     * headers or HTML meta/JS redirects (using unfiltered 1.1.1.1 resolution) so user-clicked links
     * open their clean destination page even when AdGuard DNS blocks the intermediate click tracker.
     */
    fun resolveCleanRedirectAsync(
        startUrl: String,
        referer: String?,
        userAgent: String,
        onResult: (String?) -> Unit
    ) {
        try {
            prewarmExecutor.execute {
                val resolved = resolveCleanRedirectDestination(startUrl, referer, userAgent)
                onResult(resolved)
            }
        } catch (_: Throwable) {
            onResult(extractCleanRedirectTarget(startUrl))
        }
    }

    internal fun resolveCleanRedirectDestination(
        startUrl: String,
        referer: String?,
        userAgent: String
    ): String? {
        extractCleanRedirectTarget(startUrl)?.let { return it }
        var currentUrl = startUrl.trim()
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()

        repeat(6) {
            val currentHost = runCatching { android.net.Uri.parse(currentUrl).host }.getOrNull()
            if (!currentHost.isNullOrBlank() && isPurePopunderAdHost(currentHost)) {
                return null
            }
            extractCleanRedirectTarget(currentUrl)?.let { unwrapped ->
                val unwrappedHost = runCatching { android.net.Uri.parse(unwrapped).host }.getOrNull()
                if (!unwrappedHost.isNullOrBlank() && !isPurePopunderAdHost(unwrappedHost)) {
                    return unwrapped
                }
            }

            try {
                val reqBuilder = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", userAgent.ifBlank { "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36" })
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Range", "bytes=0-8191")
                    .get()
                if (!referer.isNullOrBlank() && referer.startsWith("http", ignoreCase = true)) {
                    reqBuilder.header("Referer", referer)
                }
                val cookies = cookieManager?.let { runCatching { it.getCookie(currentUrl) }.getOrNull() }
                if (!cookies.isNullOrBlank()) {
                    reqBuilder.header("Cookie", cookies)
                }

                redirectResolverClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val code = resp.code
                    if (code in 300..399) {
                        val loc = resp.header("Location")?.trim()
                        if (loc.isNullOrBlank()) return@repeat
                        val nextUrl = resp.request.url.resolve(loc)?.toString() ?: loc
                        val nextHost = runCatching { android.net.Uri.parse(nextUrl).host }.getOrNull()
                        if (nextHost.isNullOrBlank() || isPurePopunderAdHost(nextHost)) {
                            return null
                        }
                        extractCleanRedirectTarget(nextUrl)?.let { return it }
                        if (!isBuiltInAdGuardSinkholeHost(nextHost) && !isHostSinkholedByAdGuard(nextHost)) {
                            return nextUrl
                        }
                        currentUrl = nextUrl
                    } else if (code in 200..299) {
                        val contentType = resp.header("Content-Type")?.lowercase(Locale.US).orEmpty()
                        if (contentType.contains("html") || contentType.isBlank()) {
                            val snippetBytes = runCatching {
                                resp.body?.source()?.let { src ->
                                    src.request(8192)
                                    val buf = src.buffer.clone()
                                    val count = minOf(buf.size, 8192L)
                                    buf.readByteArray(count)
                                }
                            }.getOrNull()
                            val snippet = snippetBytes?.toString(Charsets.UTF_8).orEmpty()
                            val metaOrJsNext = extractHtmlRedirectTarget(snippet, currentUrl)
                            if (!metaOrJsNext.isNullOrBlank()) {
                                val nextHost = runCatching { android.net.Uri.parse(metaOrJsNext).host }.getOrNull()
                                if (!nextHost.isNullOrBlank() && !isPurePopunderAdHost(nextHost)) {
                                    extractCleanRedirectTarget(metaOrJsNext)?.let { return it }
                                    if (!isBuiltInAdGuardSinkholeHost(nextHost)) {
                                        return metaOrJsNext
                                    }
                                    currentUrl = metaOrJsNext
                                    return@use
                                }
                            }
                        }
                        val hostNow = runCatching { android.net.Uri.parse(currentUrl).host }.getOrNull()
                        return if (!hostNow.isNullOrBlank() && !isPurePopunderAdHost(hostNow) && !isBuiltInAdGuardSinkholeHost(hostNow)) {
                            currentUrl
                        } else {
                            null
                        }
                    } else {
                        return null
                    }
                }
            } catch (_: Throwable) {
                return null
            }
        }
        val finalHost = runCatching { android.net.Uri.parse(currentUrl).host }.getOrNull()
        return if (!finalHost.isNullOrBlank() && !isPurePopunderAdHost(finalHost) && !isBuiltInAdGuardSinkholeHost(finalHost)) {
            currentUrl
        } else {
            null
        }
    }

    private fun extractHtmlRedirectTarget(htmlSnippet: String, baseUrl: String): String? {
        if (htmlSnippet.isBlank()) return null
        val metaRegex = Regex(
            """<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]+content\s*=\s*["'][^"']*?url\s*=\s*([^"'\s>]+)["']?""",
            setOf(RegexOption.IGNORE_CASE)
        )
        val metaMatch = metaRegex.find(htmlSnippet)?.groupValues?.getOrNull(1)?.trim()
        if (!metaMatch.isNullOrBlank()) {
            val resolved = resolveRelativeHttpUrl(baseUrl, metaMatch)
            if (resolved != null) return resolved
        }
        val jsLocationRegex = Regex(
            """(?:window\.|top\.|self\.)?location(?:\.href)?\s*(?:=|\.replace\(|\.assign\()\s*["'](https?://[^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE)
        )
        val jsMatch = jsLocationRegex.find(htmlSnippet)?.groupValues?.getOrNull(1)?.trim()
        if (!jsMatch.isNullOrBlank() && (jsMatch.startsWith("http://", true) || jsMatch.startsWith("https://", true))) {
            return jsMatch
        }
        return null
    }

    private fun resolveRelativeHttpUrl(baseUrl: String, candidate: String): String? {
        val clean = candidate.replace("&amp;", "&").trim().removeSurrounding("'").removeSurrounding("\"")
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean
        return runCatching { java.net.URI(baseUrl).resolve(clean).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun checkIfSystemDnsPoisonedOrFailed(normalizedHost: String) {
        if (systemDnsFailedHosts.containsKey(normalizedHost)) return
        val failedOrPoisoned = try {
            val sysAddrs = Dns.SYSTEM.lookup(normalizedHost)
            sysAddrs.isEmpty() || sysAddrs.all { addr -> isBogonOrSinkholeAddress(addr) }
        } catch (_: Throwable) {
            true
        }
        systemDnsFailedHosts[normalizedHost] = failedOrPoisoned
        if (failedOrPoisoned) {
            forcedTunnelHosts.add(normalizedHost)
        }
    }

    /**
     * Determines whether a browser WebView request should be tunneled through [browserTunnelClient]
     * using our active Custom DNS resolver (because System DNS is blocked/poisoned for this host).
     */
    fun shouldTunnelBrowserRequest(
        hostname: String?,
        method: String?,
        requestHeaders: Map<String, String>?
    ): Boolean {
        if (!isAnyCustomDnsActive) return false
        val m = method?.uppercase(Locale.US) ?: "GET"
        if (m != "GET" && m != "HEAD") return false
        val normalized = hostname?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return false
        if (normalized.isBlank() || normalized == "localhost" || isIpLiteral(normalized)) return false

        // Do not intercept WebSocket upgrades or EventSource streams
        val upgrade = requestHeaders?.entries?.firstOrNull { it.key.equals("Upgrade", true) }?.value
        if (upgrade.equals("websocket", ignoreCase = true)) return false
        val accept = requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value
        if (accept?.contains("text/event-stream", ignoreCase = true) == true) return false

        if (forcedTunnelHosts.contains(normalized) || systemDnsFailedHosts[normalized] == true) {
            return true
        }
        return false
    }

    /**
     * Executes an HTTP/HTTPS GET/HEAD request through [browserTunnelClient] backed by the active
     * Custom DNS (`1.1.1.1`, `94.140.14.14`, or `94.140.14.140`), preserving cookies, byte-range
     * `206 Partial Content` streaming headers, and CORS headers so ISP-blocked websites and video CDNs
     * work seamlessly inside Android WebView.
     */
    fun executeBrowserTunnelRequest(
        requestUrl: String,
        method: String?,
        requestHeaders: Map<String, String>?,
        isMainFrame: Boolean,
        defaultUserAgent: String
    ): WebResourceResponse? {
        return try {
            val reqBuilder = Request.Builder()
                .url(requestUrl)
                .method(method?.uppercase(Locale.US) ?: "GET", null)

            var hasUserAgent = false
            var hasCookie = false
            requestHeaders?.forEach { (k, v) ->
                // Skip hop-by-hop or encoding headers so OkHttp handles decompression transparently
                if (!k.equals("Accept-Encoding", ignoreCase = true) &&
                    !k.equals("Host", ignoreCase = true) &&
                    !k.equals("Connection", ignoreCase = true)
                ) {
                    reqBuilder.header(k, v)
                    if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                    if (k.equals("Cookie", ignoreCase = true)) hasCookie = true
                }
            }
            if (!hasUserAgent && defaultUserAgent.isNotBlank()) {
                reqBuilder.header("User-Agent", defaultUserAgent)
            }
            val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
            if (!hasCookie && cookieManager != null) {
                val cookieStr = runCatching { cookieManager.getCookie(requestUrl) }.getOrNull()
                if (!cookieStr.isNullOrBlank()) {
                    reqBuilder.header("Cookie", cookieStr)
                }
            }

            val response = browserTunnelClient.newCall(reqBuilder.build()).execute()
            val finalUrl = response.request.url.toString()

            // Synchronize Set-Cookie headers back into WebView CookieManager
            if (cookieManager != null) {
                val setCookies = response.headers("Set-Cookie")
                for (cookie in setCookies) {
                    runCatching {
                        cookieManager.setCookie(finalUrl, cookie)
                        if (finalUrl != requestUrl) {
                            cookieManager.setCookie(requestUrl, cookie)
                        }
                    }
                }
            }

            val rawContentType = response.header("Content-Type") ?: guessMimeTypeFromUrl(finalUrl)
            val mimeType = rawContentType.substringBefore(';').trim().ifBlank { "text/html" }
            val charset = rawContentType
                .substringAfter("charset=", "")
                .substringBefore(';')
                .trim()
                .removeSurrounding("\"")
                .ifBlank {
                    if (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("javascript") || mimeType.contains("xml")) {
                        "UTF-8"
                    } else {
                        null
                    }
                }

            val respHeaders = LinkedHashMap<String, String>()
            for (i in 0 until response.headers.size) {
                val name = response.headers.name(i)
                val value = response.headers.value(i)
                // Strip frame-blocking headers on tunneled responses and let WebView stream smoothly
                if (!name.equals("Content-Encoding", ignoreCase = true) &&
                    !name.equals("Transfer-Encoding", ignoreCase = true) &&
                    !name.equals("Content-Security-Policy", ignoreCase = true)
                ) {
                    respHeaders[name] = value
                }
            }
            // Ensure permissive CORS and byte-range seek support for video/audio segments and XHR subresources
            if (!isMainFrame) {
                respHeaders.putIfAbsent("Access-Control-Allow-Origin", "*")
                respHeaders.putIfAbsent("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
                respHeaders.putIfAbsent("Access-Control-Allow-Headers", "*")
                respHeaders.putIfAbsent("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
            }
            val lowerUrl = finalUrl.lowercase(Locale.US)
            if (mimeType.startsWith("video/", ignoreCase = true) ||
                mimeType.startsWith("audio/", ignoreCase = true) ||
                isLikelyMediaStreamAssetUrl(lowerUrl)
            ) {
                respHeaders.putIfAbsent("Accept-Ranges", "bytes")
            }

            val statusCode = response.code.coerceIn(100, 599)
            val reasonPhrase = response.message.ifBlank {
                when (statusCode) {
                    200 -> "OK"
                    206 -> "Partial Content"
                    204 -> "No Content"
                    304 -> "Not Modified"
                    301, 302, 307, 308 -> "Redirect"
                    404 -> "Not Found"
                    else -> "OK"
                }
            }

            val bodyStream = if (statusCode == 204 || statusCode == 304) {
                runCatching { response.close() }
                ByteArrayInputStream(ByteArray(0))
            } else {
                val rawStream = response.body?.byteStream()
                if (rawStream == null) {
                    runCatching { response.close() }
                    ByteArrayInputStream(ByteArray(0))
                } else {
                    object : java.io.FilterInputStream(rawStream) {
                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                runCatching { response.close() }
                            }
                        }
                    }
                }
            }
            WebResourceResponse(
                mimeType,
                charset,
                statusCode,
                reasonPhrase,
                respHeaders,
                bodyStream
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun prewarmCommonHosts(scope: CoroutineScope) {
        if (!isAnyCustomDnsActive) return
        scope.launch(Dispatchers.IO) {
            COMMON_WARMUP_HOSTS.forEach { host ->
                if (!isAnyCustomDnsActive) return@launch
                try {
                    lookup(host)
                } catch (_: Exception) {}
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val normalizedHost = hostname.trim().lowercase(Locale.US).trimEnd('.')
        if (!isAnyCustomDnsActive || normalizedHost.isBlank() || normalizedHost == "localhost" || isIpLiteral(normalizedHost)) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val useAdGuardDefault = isAdGuardDnsEnabled && !isEnabled && !isUnfilteredAdGuardEnabled
        val useAdGuardUnfiltered = isUnfilteredAdGuardEnabled && !isEnabled && !isAdGuardDnsEnabled

        // When AdGuard Default DNS (94.140.14.14) is active, check instant sinkhole rules first
        if (useAdGuardDefault && !isMediaStreamingOrEssentialHost(normalizedHost) && isBuiltInAdGuardSinkholeHost(normalizedHost)) {
            val now = SystemClock.elapsedRealtime()
            dnsCache[normalizedHost] = CachedDnsEntry(
                addresses = emptyList(),
                expiresAtElapsedMs = now + 300_000L,
                isSinkholed = true
            )
            blockedCounter.incrementAndGet()
            publishTelemetry()
            throw UnknownHostException("Blocked by AdGuard DNS (94.140.14.14): $hostname")
        }

        val now = SystemClock.elapsedRealtime()
        val cached = dnsCache[normalizedHost]
        if (cached != null && now < cached.expiresAtElapsedMs) {
            if (cached.isSinkholed && useAdGuardDefault) {
                cacheHitCounter.incrementAndGet()
                publishTelemetry()
                throw UnknownHostException("Blocked by AdGuard DNS (94.140.14.14): $hostname")
            }
            if (cached.addresses.isNotEmpty()) {
                cacheHitCounter.incrementAndGet()
                publishTelemetry()
                return cached.addresses
            }
        }

        val lock = lookupLocks.computeIfAbsent(normalizedHost) { Any() }
        synchronized(lock) {
            val rechecked = dnsCache[normalizedHost]
            val recheckNow = SystemClock.elapsedRealtime()
            if (rechecked != null && recheckNow < rechecked.expiresAtElapsedMs) {
                if (rechecked.isSinkholed && useAdGuardDefault) {
                    cacheHitCounter.incrementAndGet()
                    publishTelemetry()
                    throw UnknownHostException("Blocked by AdGuard DNS (94.140.14.14): $hostname")
                }
                if (rechecked.addresses.isNotEmpty()) {
                    cacheHitCounter.incrementAndGet()
                    publishTelemetry()
                    return rechecked.addresses
                }
            }

            val startMs = SystemClock.elapsedRealtime()
            val resolvedAddresses = LinkedHashSet<InetAddress>()
            var ttlSeconds = DEFAULT_TTL_SECONDS
            var sinkholed = false
            val tryUdpFirst = SystemClock.elapsedRealtime() >= udpBypassUntilElapsedMs
            val tryDoh = SystemClock.elapsedRealtime() >= dohBypassUntilElapsedMs

            if (useAdGuardDefault) {
                // 1. Fast UDP/53 query to AdGuard Default DNS (94.140.14.14)
                if (tryUdpFirst) {
                    val udpResult = queryDnsUdpWire(hostname, ADGUARD_DEFAULT_PRIMARY_BYTES)
                    if (udpResult != null && !udpResult.isTruncated) {
                        udpConsecutiveFailures.set(0)
                        if (udpResult.isSinkholed && !isMediaStreamingOrEssentialHost(normalizedHost)) {
                            sinkholed = true
                            ttlSeconds = udpResult.ttlSeconds
                        } else if (udpResult.addresses.isNotEmpty()) {
                            resolvedAddresses.addAll(udpResult.addresses)
                            ttlSeconds = udpResult.ttlSeconds
                        }
                    } else if (udpResult == null) {
                        recordUdpFailure()
                    }
                }

                // 2. AdGuard Default DoH (https://dns.adguard-dns.com/dns-query) if UDP was blocked/truncated/empty
                if (resolvedAddresses.isEmpty() && !sinkholed && tryDoh) {
                    val dohResult = queryAdGuardDohWire(hostname, ADGUARD_DEFAULT_HOST)
                    if (dohResult != null) {
                        dohConsecutiveFailures.set(0)
                        if (dohResult.isSinkholed && !isMediaStreamingOrEssentialHost(normalizedHost)) {
                            sinkholed = true
                            ttlSeconds = dohResult.ttlSeconds
                        } else if (dohResult.addresses.isNotEmpty()) {
                            resolvedAddresses.addAll(dohResult.addresses)
                            ttlSeconds = dohResult.ttlSeconds
                        }
                    } else {
                        recordDohFailure()
                    }
                }
            } else if (useAdGuardUnfiltered) {
                // 1. Fast UDP/53 query to Unfiltered AdGuard DNS (94.140.14.140 / 94.140.14.141)
                if (tryUdpFirst) {
                    val udpResult = queryDnsUdpWire(hostname, ADGUARD_UNFILTERED_PRIMARY_BYTES)
                        ?: queryDnsUdpWire(hostname, ADGUARD_UNFILTERED_SECONDARY_BYTES)
                    if (udpResult != null && !udpResult.isTruncated && udpResult.addresses.isNotEmpty()) {
                        udpConsecutiveFailures.set(0)
                        resolvedAddresses.addAll(udpResult.addresses)
                        ttlSeconds = udpResult.ttlSeconds
                    } else if (udpResult == null) {
                        recordUdpFailure()
                    }
                }

                // 2. Unfiltered AdGuard DoH (https://unfiltered.adguard-dns.com/dns-query)
                if (resolvedAddresses.isEmpty() && tryDoh) {
                    val dohResult = queryAdGuardDohWire(hostname, ADGUARD_UNFILTERED_HOST)
                    if (dohResult != null && dohResult.addresses.isNotEmpty()) {
                        dohConsecutiveFailures.set(0)
                        resolvedAddresses.addAll(dohResult.addresses)
                        ttlSeconds = dohResult.ttlSeconds
                    } else {
                        recordDohFailure()
                    }
                }
            } else {
                // 1. Fast UDP/53 query to Cloudflare WARP DNS (1.1.1.1 / 1.0.0.1)
                if (tryUdpFirst) {
                    val udpResult = queryDnsUdpWire(hostname, PRIMARY_IPV4_BYTES)
                        ?: queryDnsUdpWire(hostname, SECONDARY_IPV4_BYTES)
                    if (udpResult != null && !udpResult.isTruncated && udpResult.addresses.isNotEmpty()) {
                        udpConsecutiveFailures.set(0)
                        resolvedAddresses.addAll(udpResult.addresses)
                        ttlSeconds = udpResult.ttlSeconds
                    } else if (udpResult == null) {
                        recordUdpFailure()
                    }
                }

                // 2. Cloudflare DoH (JSON API + RFC 8484 Wireformat fallback)
                if (resolvedAddresses.isEmpty() && tryDoh) {
                    val dohResult = queryCloudflareDoh(hostname)
                    if (dohResult != null && dohResult.first.isNotEmpty()) {
                        dohConsecutiveFailures.set(0)
                        resolvedAddresses.addAll(dohResult.first)
                        ttlSeconds = dohResult.second
                    } else {
                        val wireDoh = queryAdGuardDohWire(hostname, PRIMARY_IPV4)
                        if (wireDoh != null && wireDoh.addresses.isNotEmpty()) {
                            dohConsecutiveFailures.set(0)
                            resolvedAddresses.addAll(wireDoh.addresses)
                            ttlSeconds = wireDoh.ttlSeconds
                        } else {
                            recordDohFailure()
                        }
                    }
                }
            }

            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            lastLatencyMsAtomic.set(elapsed)

            // If AdGuard DNS (94.140.14.14) sinkholed this domain, cache the sinkhole and do NOT fall back to System DNS!
            if (sinkholed && useAdGuardDefault) {
                val boundedTtlMs = (ttlSeconds.coerceIn(60L, 600L)) * 1000L
                dnsCache[normalizedHost] = CachedDnsEntry(
                    addresses = emptyList(),
                    expiresAtElapsedMs = SystemClock.elapsedRealtime() + boundedTtlMs,
                    isSinkholed = true
                )
                resolvedCounter.incrementAndGet()
                blockedCounter.incrementAndGet()
                publishTelemetry()
                throw UnknownHostException("Blocked by AdGuard DNS (94.140.14.14): $hostname")
            }

            // 3. Only fall back to System DNS if Custom DNS returned no addresses (e.g. local LAN host or offline)
            if (resolvedAddresses.isEmpty()) {
                val sysAddrs = Dns.SYSTEM.lookup(hostname)
                sysAddrs.forEach { addr ->
                    if (!isBogonOrSinkholeAddress(addr)) {
                        resolvedAddresses.add(InetAddress.getByAddress(hostname, addr.address))
                    }
                }
                if (resolvedAddresses.isEmpty()) {
                    return sysAddrs
                }
            }

            // Prioritize IPv4 addresses first (Happy Eyeballs) to avoid broken IPv6 carrier routes
            val sorted = resolvedAddresses.sortedBy { addr ->
                if (addr is Inet4Address) 0 else 1
            }

            resolvedCounter.incrementAndGet()
            val boundedTtlMs = (ttlSeconds.coerceIn(60L, 600L)) * 1000L
            dnsCache[normalizedHost] = CachedDnsEntry(
                addresses = sorted,
                expiresAtElapsedMs = SystemClock.elapsedRealtime() + boundedTtlMs,
                isSinkholed = false
            )
            publishTelemetry()
            return sorted
        }
    }

    private fun recordUdpFailure() {
        val fails = udpConsecutiveFailures.incrementAndGet()
        if (fails >= 1) {
            // Carrier/router blocks UDP port 53 -> use encrypted DoH directly for next 10 minutes
            udpBypassUntilElapsedMs = SystemClock.elapsedRealtime() + 600_000L
        }
    }

    private fun recordDohFailure() {
        val fails = dohConsecutiveFailures.incrementAndGet()
        if (fails >= 3) {
            // Avoid stalling WebView resource loading if remote DoH endpoint is unreachable on current network
            dohBypassUntilElapsedMs = SystemClock.elapsedRealtime() + 45_000L
        }
    }

    private fun publishTelemetry() {
        _telemetryFlow.value = WarpDnsTelemetry(
            isEnabled = isEnabled,
            isAdGuardDnsEnabled = isAdGuardDnsEnabled,
            isUnfilteredAdGuardEnabled = isUnfilteredAdGuardEnabled,
            resolvedHostCount = resolvedCounter.get(),
            cacheHitCount = cacheHitCounter.get(),
            blockedHostCount = blockedCounter.get(),
            lastLatencyMs = lastLatencyMsAtomic.get(),
            activeResolverLabel = buildResolverLabel(isEnabled, isAdGuardDnsEnabled, isUnfilteredAdGuardEnabled)
        )
    }

    private fun buildResolverLabel(warpOn: Boolean, adGuardOn: Boolean, adGuardUnfilteredOn: Boolean): String {
        return when {
            warpOn -> "1.1.1.1 • Cloudflare WARP (DoH + UDP)"
            adGuardOn -> "94.140.14.14 • AdGuard DNS"
            adGuardUnfilteredOn -> "94.140.14.140 • Unfiltered AdGuard DNS"
            else -> "System Default DNS"
        }
    }

    /**
     * Queries Cloudflare DoH JSON API (`https://1.1.1.1/dns-query?name=...&type=A`).
     */
    internal fun queryCloudflareDoh(hostname: String): Pair<List<InetAddress>, Long>? {
        return try {
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
                parseDohJsonResponse(hostname, body)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Queries AdGuard / RFC 8484 DoH (`https://dns.adguard-dns.com/dns-query` or `https://unfiltered.adguard-dns.com/dns-query`)
     * returning full [DnsWireResult] including sinkhole detection (`0.0.0.0` / `NXDOMAIN`).
     */
    internal fun queryAdGuardDohWire(hostname: String, dohHost: String = ADGUARD_UNFILTERED_HOST): DnsWireResult? {
        return try {
            val labels = hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
            if (labels.isEmpty()) return null
            val wirePacket = buildDnsQueryPacket(labels, qType = 1)
            val base64Url = android.util.Base64.encodeToString(
                wirePacket,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            val url = "https://$dohHost/dns-query?dns=$base64Url"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-message")
                .get()
                .build()

            dohBootstrapClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                parseDnsWireResponseDetailed(hostname, bytes, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun queryAdGuardDoh(hostname: String, dohHost: String = ADGUARD_UNFILTERED_HOST): Pair<List<InetAddress>, Long>? {
        val res = queryAdGuardDohWire(hostname, dohHost) ?: return null
        return if (res.addresses.isEmpty()) null else (res.addresses to res.ttlSeconds)
    }

    internal fun queryUnfilteredAdGuardDoh(hostname: String): Pair<List<InetAddress>, Long>? =
        queryAdGuardDoh(hostname, ADGUARD_UNFILTERED_HOST)

    companion object {
        const val PRIMARY_IPV4 = "1.1.1.1"
        const val SECONDARY_IPV4 = "1.0.0.1"
        const val WARP_DOT_HOSTNAME = "1dot1dot1dot1.cloudflare-dns.com"
        const val ADGUARD_DEFAULT_PRIMARY_IPV4 = "94.140.14.14"
        const val ADGUARD_DEFAULT_SECONDARY_IPV4 = "94.140.15.15"
        const val ADGUARD_DEFAULT_HOST = "dns.adguard-dns.com"
        const val ADGUARD_UNFILTERED_PRIMARY_IPV4 = "94.140.14.140"
        const val ADGUARD_UNFILTERED_SECONDARY_IPV4 = "94.140.14.141"
        const val ADGUARD_UNFILTERED_HOST = "unfiltered.adguard-dns.com"
        private const val DEFAULT_TTL_SECONDS = 300L

        private val PRIMARY_IPV4_BYTES = byteArrayOf(1, 1, 1, 1)
        private val SECONDARY_IPV4_BYTES = byteArrayOf(1, 0, 0, 1)
        private val ADGUARD_DEFAULT_PRIMARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 14.toByte(), 14.toByte())
        private val ADGUARD_DEFAULT_SECONDARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 15.toByte(), 15.toByte())
        private val ADGUARD_UNFILTERED_PRIMARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 14.toByte(), 140.toByte())
        private val ADGUARD_UNFILTERED_SECONDARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 14.toByte(), 141.toByte())

        private val TRANSPARENT_1X1_GIF = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
            0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
        )

        private val COMMON_WARMUP_HOSTS = listOf(
            "www.google.com",
            "www.youtube.com",
            "m.youtube.com",
            "patreon.com",
            "www.patreon.com",
            "bilibili.tv",
            "www.bilibili.tv",
            "www.dailymotion.com",
            "vimeo.com",
            "player.vimeo.com",
            "pawchive.pw"
        )

        // Never block legitimate video/audio streaming CDNs or core website domains
        private val ESSENTIAL_STREAMING_HOST_SUFFIXES = listOf(
            "googlevideo.com",
            "youtube.com",
            "ytimg.com",
            "ggpht.com",
            "youtu.be",
            "bilibili.tv",
            "bilibili.com",
            "bilivideo.com",
            "hdslb.com",
            "akamaized.net",
            "akamaihd.net",
            "cloudfront.net",
            "cloudflare.com",
            "cdnjs.cloudflare.com",
            "jsdelivr.net",
            "unpkg.com",
            "fastly.net",
            "vimeo.com",
            "vimeocdn.com",
            "dailymotion.com",
            "dmcdn.net",
            "twitch.tv",
            "ttvnw.net",
            "jwpcdn.com",
            "jwplatform.com",
            "jwpsrv.com",
            "vjs.zencdn.net",
            "patreon.com",
            "patreonusercontent.com",
            "tiktok.com",
            "tiktokcdn.com",
            "fbcdn.net",
            "cdninstagram.com",
            "twimg.com",
            "twitter.com",
            "x.com",
            "reddit.com",
            "redd.it",
            "redditmedia.com",
            "google.com",
            "gstatic.com",
            "googleapis.com",
            "github.com",
            "githubusercontent.com",
            "archive.org",
            "mega.nz",
            "b-cdn.net",
            "bunnycdn.com",
            "bunnyinfra.net",
            "cloudflarestorage.com",
            "workers.dev",
            "pages.dev",
            "mux.com",
            "stream.mux.com",
            "bitmovin.com",
            "theoplayer.com",
            "videojs.com",
            "plyr.io",
            "dashjs.org",
            "cdn77.org",
            "gcorelabs.com",
            "hwcdn.net",
            "stackpathdns.com",
            "edgecastcdn.net",
            "llnwd.net",
            "brightcove.net",
            "brightcovecdn.com",
            "kaltura.com",
            "wistia.com",
            "wistia.net",
            "vidyard.com",
            "rumble.com",
            "odysee.com",
            "kick.com",
            "twitchcdn.net",
            "vidcdn.net",
            "streamtape.com",
            "filemoon.sx",
            "doodstream.com",
            "mixdrop.co",
            "mp4upload.com",
            "fonts.googleapis.com",
            "fonts.gstatic.com",
            "ajax.googleapis.com",
            "code.jquery.com",
            "use.fontawesome.com",
            "maxcdn.bootstrapcdn.com",
            "stackpath.bootstrapcdn.com",
            "challenges.cloudflare.com",
            "hcaptcha.com",
            "recaptcha.net"
        )

        // High-precision AdGuard DNS sinkhole domain suffixes for 0ms ad/tracker/popunder blocking when AdGuard DNS (94.140.14.14) is active
        private val ADGUARD_SINKHOLE_DOMAIN_SUFFIXES = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "2mdn.net",
            "adnxs.com",
            "adsrvr.org",
            "advertising.com",
            "taboola.com",
            "outbrain.com",
            "criteo.com",
            "criteo.net",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "smartadserver.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "adsterra.com",
            "exoclick.com",
            "exosrv.com",
            "juicyads.com",
            "trafficjunky.net",
            "trafficjunky.com",
            "hilltopads.net",
            "clickadu.com",
            "adsco.re",
            "adtng.com",
            "realsrv.com",
            "tsyndicate.com",
            "TrafficStars.com".lowercase(Locale.US),
            "mgid.com",
            "revcontent.com",
            "zemanta.com",
            "teads.tv",
            "moatads.com",
            "scorecardresearch.com",
            "quantserve.com",
            "amazon-adsystem.com",
            "adcolony.com",
            "applovin.com",
            "unityads.unity3d.com",
            "vungle.com",
            "chartboost.com",
            "inmobi.com",
            "mopub.com",
            "StartAppService.com".lowercase(Locale.US),
            "mc.yandex.ru",
            "an.yandex.ru",
            "ads.yahoo.com",
            "adform.net",
            "casalemedia.com",
            "contextweb.com",
            "indexww.com",
            "lijit.com",
            "media.net",
            "onetag-sys.com",
            "sharethrough.com",
            "sovrn.com",
            "spotxchange.com",
            "triplelift.com",
            "yieldmo.com"
        )

        fun isMediaStreamingOrEssentialHost(host: String): Boolean {
            val h = host.trim().lowercase(Locale.US).trimEnd('.')
            return ESSENTIAL_STREAMING_HOST_SUFFIXES.any { suffix ->
                h == suffix || h.endsWith(".$suffix")
            }
        }

        fun isBuiltInAdGuardSinkholeHost(host: String): Boolean {
            val h = host.trim().lowercase(Locale.US).trimEnd('.')
            if (h.isBlank() || isMediaStreamingOrEssentialHost(h)) return false
            if (ADGUARD_SINKHOLE_DOMAIN_SUFFIXES.any { suffix -> h == suffix || h.endsWith(".$suffix") }) {
                return true
            }
            // Match common ad-serving subdomains like ads.example.com, adserver.example.com, pop.example.com
            if (h.startsWith("ads.") || h.startsWith("adserver.") || h.startsWith("adservice.") ||
                h.startsWith("popunder.") || h.startsWith("telemetry.") || h.startsWith("pagead2.")
            ) {
                return true
            }
            return false
        }

        fun hasMediaStreamingHeaders(requestHeaders: Map<String, String>?): Boolean {
            if (requestHeaders.isNullOrEmpty()) return false
            for ((k, v) in requestHeaders) {
                if (k.equals("Range", ignoreCase = true) || k.equals("If-Range", ignoreCase = true)) {
                    return true
                }
                if (k.equals("Accept", ignoreCase = true)) {
                    val low = v.lowercase(Locale.US)
                    if (low.contains("video/") ||
                        low.contains("audio/") ||
                        low.contains("mpegurl") ||
                        low.contains("dash+xml") ||
                        low.contains("mp2t")
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        fun isPurePopunderAdHost(host: String): Boolean {
            val h = host.trim().lowercase(Locale.US).trimEnd('.')
            val popunderRoots = listOf(
                "popads.net",
                "popcash.net",
                "propellerads.com",
                "adsterra.com",
                "exoclick.com",
                "exosrv.com",
                "juicyads.com",
                "trafficjunky.net",
                "trafficjunky.com",
                "hilltopads.net",
                "clickadu.com",
                "adsco.re",
                "adtng.com",
                "realsrv.com",
                "tsyndicate.com",
                "trafficstars.com"
            )
            return popunderRoots.any { root -> h == root || h.endsWith(".$root") } ||
                h.startsWith("popunder.")
        }

        /**
         * Unwraps ad/tracker click-redirect links (e.g. `?adurl=https://...`, `?url=https://...`,
         * `?redirect=https://...`, base64-encoded targets, or semicolon-embedded URLs) so tapping a link
         * containing an ad wrapper navigates directly to the clean destination instead of failing to load.
         */
        fun extractCleanRedirectTarget(rawUrl: String): String? {
            var current = rawUrl.trim()
            val redirectParamKeys = setOf(
                "adurl", "url", "u", "q", "redirect", "redirect_url", "redirect_uri",
                "redir", "dest", "destination", "target", "goto", "link", "out",
                "next", "to", "r", "linkurl", "ds_dest_url", "surl", "murl", "clickurl",
                "data", "ref_url", "continue"
            )
            repeat(5) {
                val uri = runCatching { android.net.Uri.parse(current) }.getOrNull() ?: return@repeat
                val candidateFromQuery = runCatching {
                    uri.queryParameterNames.firstNotNullOfOrNull { key ->
                        val rawVal = uri.getQueryParameter(key)?.trim()
                        if (!rawVal.isNullOrBlank() && key.lowercase(Locale.US) in redirectParamKeys) {
                            decodePossibleWrappedHttpUrl(rawVal)
                        } else if (rawVal.isNullOrBlank() &&
                            (key.startsWith("http://", true) || key.startsWith("https://", true))
                        ) {
                            key.trim()
                        } else {
                            null
                        }
                    }
                }.getOrNull()

                // DoubleClick & ad servers sometimes append the target URL after ';' or '?' directly (e.g. `;https://...`)
                val candidateInline = if (candidateFromQuery.isNullOrBlank()) {
                    val semiIdx = current.indexOf(";http", ignoreCase = true)
                    if (semiIdx >= 0) {
                        val sub = current.substring(semiIdx + 1).trim()
                        decodePossibleWrappedHttpUrl(sub)
                    } else {
                        val qHttpIdx = current.indexOf("?http", ignoreCase = true)
                        if (qHttpIdx >= 0) {
                            decodePossibleWrappedHttpUrl(current.substring(qHttpIdx + 1).trim())
                        } else {
                            null
                        }
                    }
                } else {
                    candidateFromQuery
                }

                val nextCandidate = candidateInline ?: return@repeat
                val candidateHost = runCatching { android.net.Uri.parse(nextCandidate).host }.getOrNull().orEmpty()
                if (candidateHost.isNotBlank() && nextCandidate != current) {
                    current = nextCandidate
                } else {
                    return@repeat
                }
            }
            if (current == rawUrl.trim()) return null
            val finalHost = runCatching { android.net.Uri.parse(current).host }.getOrNull()
            if (finalHost.isNullOrBlank() || isBuiltInAdGuardSinkholeHost(finalHost)) return null
            return current
        }

        private fun decodePossibleWrappedHttpUrl(rawValue: String): String? {
            var candidate = rawValue.trim()
            if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) {
                return candidate
            }
            // Handle double percent-encoded URLs (e.g. https%3A%2F%2F...)
            if (candidate.startsWith("http%3a", true) || candidate.startsWith("https%3a", true)) {
                val decoded = runCatching { java.net.URLDecoder.decode(candidate, "UTF-8") }.getOrNull()?.trim()
                if (!decoded.isNullOrBlank() && (decoded.startsWith("http://", true) || decoded.startsWith("https://", true))) {
                    return decoded
                }
            }
            // Handle base64 / URL-safe base64 wrapped URLs (e.g. aHR0cHM6Ly8...)
            if (candidate.length >= 12 && (candidate.startsWith("aHR0cDov") || candidate.startsWith("aHR0cHM6"))) {
                val normalizedB64 = candidate.replace('-', '+').replace('_', '/')
                val padded = normalizedB64.padEnd(((normalizedB64.length + 3) / 4) * 4, '=')
                val decodedBytes = runCatching {
                    android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
                }.getOrNull()
                val decodedText = decodedBytes?.toString(Charsets.UTF_8)?.trim()
                if (!decodedText.isNullOrBlank() &&
                    (decodedText.startsWith("http://", true) || decodedText.startsWith("https://", true))
                ) {
                    return decodedText
                }
            }
            return null
        }

        fun isLikelyMediaStreamAssetUrl(url: String): Boolean {
            val fullLower = url.lowercase(Locale.US)
            val pathLower = fullLower.substringBefore('?')
            return pathLower.endsWith(".m3u8") ||
                pathLower.endsWith(".mpd") ||
                pathLower.endsWith(".ts") ||
                pathLower.endsWith(".m4s") ||
                pathLower.endsWith(".m4f") ||
                pathLower.endsWith(".fmp4") ||
                pathLower.endsWith(".mp4") ||
                pathLower.endsWith(".m4v") ||
                pathLower.endsWith(".webm") ||
                pathLower.endsWith(".mkv") ||
                pathLower.endsWith(".mov") ||
                pathLower.endsWith(".avi") ||
                pathLower.endsWith(".flv") ||
                pathLower.endsWith(".mp3") ||
                pathLower.endsWith(".m4a") ||
                pathLower.endsWith(".aac") ||
                pathLower.endsWith(".opus") ||
                pathLower.endsWith(".flac") ||
                pathLower.endsWith(".ogg") ||
                pathLower.endsWith(".vtt") ||
                pathLower.endsWith(".srt") ||
                pathLower.endsWith(".key") ||
                fullLower.contains("/hls/") ||
                fullLower.contains("/dash/") ||
                fullLower.contains("/videoplayback") ||
                fullLower.contains("mime=video") ||
                fullLower.contains("mime=audio") ||
                fullLower.contains("playlist.m3u8") ||
                fullLower.contains("master.m3u8") ||
                fullLower.contains("chunklist") ||
                fullLower.contains("/seg-") ||
                fullLower.contains("/frag-")
        }

        fun isSameRegistrableDomain(hostA: String?, hostB: String?): Boolean {
            if (hostA.isNullOrBlank() || hostB.isNullOrBlank()) return false
            val a = hostA.trim().lowercase(Locale.US).removePrefix("www.")
            val b = hostB.trim().lowercase(Locale.US).removePrefix("www.")
            if (a == b) return true
            val partsA = a.split('.')
            val partsB = b.split('.')
            if (partsA.size >= 2 && partsB.size >= 2) {
                val tailA = partsA.takeLast(2).joinToString(".")
                val tailB = partsB.takeLast(2).joinToString(".")
                return tailA == tailB
            }
            return false
        }

        fun isBogonOrSinkholeAddress(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress) return true
            val hostAddr = addr.hostAddress ?: return true
            return hostAddr == "0.0.0.0" ||
                hostAddr == "127.0.0.1" ||
                hostAddr == "::" ||
                hostAddr == "::1" ||
                hostAddr == "0:0:0:0:0:0:0:0" ||
                hostAddr == "0:0:0:0:0:0:0:1"
        }

        private fun guessMimeTypeFromUrl(url: String): String {
            val path = url.substringBefore('?').lowercase(Locale.US)
            return when {
                path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".ts") -> "video/mp2t"
                path.endsWith(".m4s") -> "video/iso.segment"
                path.endsWith(".mp3") -> "audio/mpeg"
                path.endsWith(".m4a") -> "audio/mp4"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".json") -> "application/json"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".svg") -> "image/svg+xml"
                else -> "text/html"
            }
        }

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
                            val addr = InetAddress.getByAddress(hostname, bytes)
                            if (!isBogonOrSinkholeAddress(addr)) {
                                results.add(addr)
                            }
                        }
                    }
                }
                if (results.isEmpty()) null else (results to minTtl)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Sends an RFC 1035 UDP DNS query to the target DNS server IP:53 and returns [DnsWireResult].
         */
        internal fun queryDnsUdpWire(hostname: String, serverIpBytes: ByteArray): DnsWireResult? {
            return try {
                val labels = hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
                if (labels.isEmpty()) return null

                val packetBytes = buildDnsQueryPacket(labels, qType = 1)
                DatagramSocket().use { socket ->
                    socket.soTimeout = 250
                    val serverAddr = InetAddress.getByAddress(serverIpBytes)
                    val outPacket = DatagramPacket(packetBytes, packetBytes.size, serverAddr, 53)
                    socket.send(outPacket)

                    val recvBuf = ByteArray(1500)
                    val inPacket = DatagramPacket(recvBuf, recvBuf.size)
                    socket.receive(inPacket)
                    parseDnsWireResponseDetailed(hostname, recvBuf, inPacket.length)
                }
            } catch (_: Exception) {
                null
            }
        }

        fun queryCloudflareUdpA(hostname: String, serverIpBytes: ByteArray): Pair<List<InetAddress>, Long>? {
            val res = queryDnsUdpWire(hostname, serverIpBytes) ?: return null
            return if (res.addresses.isEmpty()) null else (res.addresses to res.ttlSeconds)
        }

        internal fun buildDnsQueryPacket(labels: List<String>, qType: Int = 1): ByteArray {
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
            dos.writeShort(qType) // QTYPE = A (1) or AAAA (28)
            dos.writeShort(1) // QCLASS = IN (1)
            dos.flush()
            return baos.toByteArray()
        }

        internal fun parseDnsWireResponseDetailed(
            hostname: String,
            buf: ByteArray,
            length: Int
        ): DnsWireResult? {
            if (length < 12) return null
            val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            val isTruncated = (flags and 0x0200) != 0
            if (isTruncated) {
                return DnsWireResult(
                    addresses = emptyList(),
                    ttlSeconds = DEFAULT_TTL_SECONDS,
                    isSinkholed = false,
                    isTruncated = true
                )
            }

            val rcode = flags and 0x0F
            // NXDOMAIN (3) or REFUSED (5) from AdGuard DNS indicates blocked/non-existent domain
            if (rcode == 3 || rcode == 5) {
                return DnsWireResult(
                    addresses = emptyList(),
                    ttlSeconds = 120L,
                    isSinkholed = true,
                    isTruncated = false
                )
            }
            if (rcode != 0) return null

            val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            if (anCount <= 0) {
                return DnsWireResult(
                    addresses = emptyList(),
                    ttlSeconds = 120L,
                    isSinkholed = false,
                    isTruncated = false
                )
            }

            var offset = 12
            // Skip Questions
            repeat(qdCount) {
                offset = skipDnsName(buf, offset, length)
                if (offset < 0 || offset + 4 > length) return null
                offset += 4
            }

            val addresses = mutableListOf<InetAddress>()
            var minTtl = DEFAULT_TTL_SECONDS
            var sawSinkholeIp = false

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
                if ((type == 1 && rdLength == 4) || (type == 28 && rdLength == 16)) {
                    val ipBytes = buf.copyOfRange(offset, offset + rdLength)
                    val addr = InetAddress.getByAddress(hostname, ipBytes)
                    if (isBogonOrSinkholeAddress(addr)) {
                        sawSinkholeIp = true
                    } else {
                        addresses.add(addr)
                        if (ttl < minTtl) minTtl = ttl
                    }
                }
                offset += rdLength
            }

            return DnsWireResult(
                addresses = addresses,
                ttlSeconds = minTtl,
                isSinkholed = sawSinkholeIp && addresses.isEmpty(),
                isTruncated = false
            )
        }

        internal fun parseDnsUdpResponse(
            hostname: String,
            buf: ByteArray,
            length: Int
        ): Pair<List<InetAddress>, Long>? {
            val detailed = parseDnsWireResponseDetailed(hostname, buf, length) ?: return null
            return if (detailed.addresses.isEmpty()) null else (detailed.addresses to detailed.ttlSeconds)
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
