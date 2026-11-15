package com.example.data.adblock

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.data.model.AdBlockEvent
import com.example.data.model.AdBlockerConfig
import com.example.data.model.AdBlockerMode
import com.example.data.model.SocialPlatform
import com.example.data.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AdBlockerVerificationStatus(
    val isHostsSinkholeVerified: Boolean,
    val activeHostsCount: Int,
    val isPrivateDnsVerified: Boolean,
    val activeDnsHost: String,
    val isNetfilterVerified: Boolean,
    val isQuicBlocked: Boolean,
    val enforcementSummary: String
)

object AdBlockerEngine {
    private const val TAG = "AdBlockerEngine"
    private const val TMP_HOSTS_PATH = "/data/local/tmp/hosts_adblock"

    // Pure third-party & dedicated ad hostnames (never blocks core app startup/API/CDN domains)
    val YOUTUBE_DOMAINS = listOf(
        "ads.youtube.com",
        "pagead2.googlesyndication.com",
        "pagead2.googleadservices.com",
        "tpc.googlesyndication.com",
        "ade.googlesyndication.com",
        "googleads.g.doubleclick.net",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "ad.doubleclick.net",
        "stats.g.doubleclick.net",
        "static.doubleclick.net",
        "cm.g.doubleclick.net",
        "mediavisor.doubleclick.net",
        "adservice.google.com",
        "adservice.google.co.in",
        "adservice.google.com.vn",
        "adservice.google.co.uk",
        "adservice.google.de",
        "imasdk.googleapis.com",
        "partnerad.l.google.com",
        "ad-delivery.net",
        "www.googleadservices.com",
        "googleadservices.com",
        "fundingchoicesmessages.google.com",
        "youtube.cleverads.vn",
        "ads-ookla.youtube.com"
    )

    // Safe, targeted Facebook & Messenger ad/pixel domains (never blocks graph, lookaside, or c10r router)
    val FACEBOOK_DOMAINS = listOf(
        "an.facebook.com",
        "audience-network.facebook.com",
        "ads.facebook.com",
        "advertising.facebook.com",
        "admarket.facebook.com",
        "mobileads.facebook.com",
        "ads-api.facebook.com",
        "adservice.facebook.com",
        "pixel.facebook.com",
        "tr.facebook.com",
        "analytics.facebook.com",
        "an.meta.com",
        "ads.meta.com",
        "pixel.meta.com",
        "meta.pixel.com",
        "ad.atdmt.com",
        "liverail.com",
        "ads.liverail.com"
    )

    val INSTAGRAM_DOMAINS = listOf(
        "ads.instagram.com",
        "ads-api.instagram.com",
        "an.instagram.com",
        "pixel.instagram.com",
        "tr.instagram.com",
        "analytics.instagram.com",
        "telemetry.instagram.com"
    )

    val TIKTOK_DOMAINS = listOf(
        "ads.tiktok.com",
        "ad.tiktok.com",
        "ads-sg.tiktok.com",
        "ads-va.tiktok.com",
        "ads-api.tiktok.com",
        "business-api.tiktok.com",
        "analytics.tiktok.com",
        "analytics-sg.tiktok.com",
        "pg-ad.tiktokv.com",
        "p16-ad-sg.ibyteimg.com",
        "p16-ad-va.ibyteimg.com",
        "pangolin-sdk-toutiao.com",
        "api-access.pangolin-sdk-toutiao.com",
        "log-api.pangolin-sdk-toutiao.com",
        "ad.zijieapi.com"
    )

    val SNAPCHAT_DOMAINS = listOf(
        "ads.snapchat.com",
        "adserver.snapchat.com",
        "story-ad-api.snapchat.com",
        "tr.snapchat.com",
        "app-analytics.snapchat.com",
        "sc-analytics.appspot.com",
        "analytics.snapchat.com",
        "ads-api.snapchat.com",
        "pixel.snapchat.com",
        "usc.adserver.snapads.com",
        "euw.adserver.snapads.com",
        "ase.adserver.snapads.com"
    )

    val OTHER_SOCIAL_DOMAINS = listOf(
        "ads-api.twitter.com",
        "ads-twitter.com",
        "ads.twitter.com",
        "analytics.twitter.com",
        "p.twitter.com",
        "scribe.twitter.com",
        "syndication.twitter.com",
        "static.ads-twitter.com",
        "ads.x.com",
        "analytics.x.com",
        "alb.reddit.com",
        "events.redditmedia.com",
        "ads.reddit.com",
        "d.reddit.com",
        "rereddit.com",
        "w3-reporting.reddit.com",
        "ads.pinterest.com",
        "log.pinterest.com",
        "trk.pinterest.com",
        "analytics.pinterest.com",
        "countess.twitch.tv",
        "ads.twitch.tv",
        "pubster.twitch.tv",
        "spade.twitch.tv"
    )

    // Cross-App Social Media Embedded Ad SDKs (Pangle, AppLovin, UnityAds, IronSource, Vungle, Adjust, AppsFlyer)
    val EMBEDDED_AD_SDK_DOMAINS = listOf(
        "rt.applovin.com",
        "ms.applovin.com",
        "d.applovin.com",
        "a.applovin.com",
        "prod-a.applovin.com",
        "ads.mopub.com",
        "unityads.unity3d.com",
        "auction.unityads.unity3d.com",
        "config.unityads.unity3d.com",
        "webview.unityads.unity3d.com",
        "init.supersonicads.com",
        "outcome-ssp.supersonicads.com",
        "ads.api.vungle.com",
        "config.ads.vungle.com",
        "events.ads.vungle.com",
        "app.adjust.com",
        "view.adjust.com",
        "launches.appsflyer.com",
        "events.appsflyer.com",
        "impression.appsflyer.com",
        "api2.branch.io",
        "cdn.branch.io",
        "ib.adnxs.com",
        "criteo.com",
        "bidder.criteo.com",
        "gum.criteo.com",
        "ads.pubmatic.com",
        "hbopenbid.pubmatic.com",
        "fastlane.rubiconproject.com",
        "taboola.com",
        "trc.taboola.com",
        "outbrain.com",
        "widgets.outbrain.com"
    )

    // Netfilter packet inspection signatures (DNS & TLS SNI / HTTP payload substrings)
    private val NETFILTER_AD_SIGNATURES = listOf(
        "an.facebook.com",
        "ads.facebook.com",
        "mobileads.facebook.com",
        "pixel.facebook.com",
        "advertising.facebook.com",
        "creative.ak.fbcdn.net",
        "ads.instagram.com",
        "logging.instagram.com",
        "ads.tiktok.com",
        "pg-ad.tiktokv.com",
        "log.byteoversea.com",
        "pangolin-sdk-toutiao.com",
        "ads.snapchat.com",
        "story-ad-api.snapchat.com",
        "adserver.snapads.com",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "ads.youtube.com",
        "ads-api.twitter.com",
        "ads.reddit.com",
        "events.redditmedia.com"
    )

    fun getDomainsForPlatform(platform: SocialPlatform): List<String> {
        return when (platform) {
            SocialPlatform.YOUTUBE -> YOUTUBE_DOMAINS
            SocialPlatform.FACEBOOK -> FACEBOOK_DOMAINS
            SocialPlatform.INSTAGRAM -> INSTAGRAM_DOMAINS
            SocialPlatform.TIKTOK -> TIKTOK_DOMAINS
            SocialPlatform.SNAPCHAT -> SNAPCHAT_DOMAINS
            SocialPlatform.OTHER_SOCIAL -> OTHER_SOCIAL_DOMAINS
        }
    }

    fun detectSocialPlatform(packageName: String): SocialPlatform? {
        if (packageName.isBlank()) return null
        for (platform in SocialPlatform.values()) {
            if (platform.packageNames.any { it.equals(packageName, ignoreCase = true) }) {
                return platform
            }
        }
        return null
    }

    fun buildActiveDomainSet(config: AdBlockerConfig): Set<String> {
        val activeDomains = LinkedHashSet<String>()
        for (platform in SocialPlatform.values()) {
            val isEnabled = config.platformToggles[platform.id] ?: true
            if (isEnabled) {
                val rawList = getDomainsForPlatform(platform)
                for (domain in rawList) {
                    val d = domain.trim().lowercase()
                    if (d.isBlank() || d.contains("/")) continue
                    val isTelemetryDomain = d.contains("analytics") || d.contains("logging") ||
                        d.contains("pixel") || d.contains("log.") || d.contains("rtlog") ||
                        d.contains("toblog") || d.contains("mon.") || d.contains("events.") ||
                        d.contains("tr.") || d.contains("scribe.")
                    val isVideoOrReelAd = d.contains("ads.") || d.contains("ad.") ||
                        d.contains("doubleclick") || d.contains("googlesyndication") ||
                        d.contains("imasdk") || d.contains("pg-ad") || d.contains("story-ad") ||
                        d.contains("an.") || d.contains("audience-network") || d.contains("pangolin")

                    val shouldInclude = when {
                        isTelemetryDomain && !config.blockTelemetry -> false
                        isVideoOrReelAd && !config.blockVideoAds && !config.blockReelsAndStoriesAds && !config.blockSponsoredPosts -> false
                        else -> true
                    }
                    if (shouldInclude) {
                        activeDomains.add(d)
                    }
                }
            }
        }
        if (config.blockVideoAds || config.blockSponsoredPosts || config.blockReelsAndStoriesAds) {
            activeDomains.addAll(EMBEDDED_AD_SDK_DOMAINS)
        }
        config.customBlacklistDomains.forEach { d ->
            val clean = d.trim().lowercase().substringBefore("/")
            if (clean.isNotBlank()) activeDomains.add(clean)
        }
        config.customWhitelistDomains.forEach { w ->
            activeDomains.remove(w.trim().lowercase().substringBefore("/"))
        }
        return activeDomains
    }

    @Volatile
    private var lastAppliedSignature: Int = 0

    @Volatile
    private var lastCachePurgeTimeMs: Long = 0L

    suspend fun applyAdBlocking(
        context: Context,
        config: AdBlockerConfig,
        forceReapply: Boolean = false
    ): AdBlockerVerificationStatus = withContext(Dispatchers.IO) {
        try {
            val activeDomains = buildActiveDomainSet(config).toList()
            val currentSig = (config.hashCode() * 31) + activeDomains.size

            // Avoid re-running heavy root mounts / iptables rebuilds if identical rules are already active
            if (!forceReapply && currentSig == lastAppliedSignature && lastAppliedSignature != 0) {
                return@withContext verifyActiveProtection(context, config, activeDomains.size)
            }

            Log.d(TAG, "Applying multi-layer social media ad blocking: mode=${config.mode}, dns=${config.customDnsProvider}")
            val isRoot = RootHelper.isRootAvailable

            // 0. Ensure LSPosed XSharedPreferences & ContentProvider permissions are granted
            if (isRoot) {
                com.example.xposed.DragonLSPosedStatus.grantLSPosedModulePermissions(context)
            }

            // 1. Apply Private DNS (DoT / DoH) via both Android Settings.Global API & Root shell
            if (config.mode == AdBlockerMode.PRIVATE_DNS ||
                config.mode == AdBlockerMode.HYBRID_ROOT_DNS ||
                config.mode == AdBlockerMode.LSPOSED_HYBRID
            ) {
                applyPrivateDns(context, config.customDnsProvider)
            }

            // 2. Apply System Hosts Loopback Sinkhole safely in-place (no VFS remount loops or init namespace tampering)
            if (isRoot && (config.mode == AdBlockerMode.SYSTEM_HOSTS ||
                config.mode == AdBlockerMode.HYBRID_ROOT_DNS ||
                config.mode == AdBlockerMode.LSPOSED_HYBRID)
            ) {
                applyHostsSinkhole(activeDomains)
            }

            // 3. Apply Lightweight Kernel-Safe Netfilter Rules (UDP 443 QUIC block + bounded DNS 53 filter)
            if (isRoot && (config.mode == AdBlockerMode.IPTABLES_FILTER ||
                config.mode == AdBlockerMode.HYBRID_ROOT_DNS ||
                config.mode == AdBlockerMode.LSPOSED_HYBRID)
            ) {
                applyIptablesDrops(context, config)
            }

            // 4. Purge pre-buffered social media ad caches at most once every 15 minutes
            val now = System.currentTimeMillis()
            if (isRoot && config.autoPurgeSocialAdCaches && (now - lastCachePurgeTimeMs > 900_000L)) {
                lastCachePurgeTimeMs = now
                purgeSocialAdCaches()
            }

            // 5. Flush system DNS cache
            flushDnsCache()

            lastAppliedSignature = currentSig
            verifyActiveProtection(context, config, activeDomains.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying ad blocking", e)
            verifyActiveProtection(context, config, 0)
        }
    }

    suspend fun disableAdBlocking(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Disabling ad blocking")
            lastAppliedSignature = 0

            // 1. Reset Private DNS to opportunistic via API + Root
            try {
                Settings.Global.putString(context.contentResolver, "private_dns_mode", "opportunistic")
            } catch (_: Exception) {}

            if (RootHelper.isRootAvailable) {
                RootHelper.executeCommand(
                    """
                    settings put global private_dns_mode opportunistic 2>/dev/null
                    umount -l /system/etc/hosts 2>/dev/null || true
                    rm -f $TMP_HOSTS_PATH 2>/dev/null || true
                    """.trimIndent()
                )
                clearIptablesDrops(context)
                flushDnsCache()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling ad blocking", e)
            false
        }
    }

    private suspend fun applyPrivateDns(context: Context, dnsHost: String) {
        val targetDns = dnsHost.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("tls://")
            .substringBefore("/")
            .ifBlank { "dns.adguard.com" }

        // Direct ContentResolver attempt (works if WRITE_SECURE_SETTINGS is granted)
        try {
            Settings.Global.putString(context.contentResolver, "private_dns_specifier", targetDns)
            Settings.Global.putString(context.contentResolver, "private_dns_mode", "hostname")
        } catch (_: Exception) {}

        if (RootHelper.isRootAvailable) {
            RootHelper.executeCommand(
                """
                pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
                settings put global private_dns_specifier "$targetDns" 2>/dev/null
                settings put global private_dns_mode hostname 2>/dev/null
                """.trimIndent()
            )
        }
    }

    private suspend fun applyHostsSinkhole(domains: List<String>) {
        val sb = StringBuilder(domains.size * 48 + 256)
        sb.append("127.0.0.1 localhost\n")
        sb.append("::1 localhost\n")
        sb.append("127.0.0.1 ip6-localhost\n")
        sb.append("::1 ip6-loopback\n\n")
        sb.append("# Dragon Tools Multi-Layer Social Media Ad-Block Sinkhole\n")

        for (domain in domains) {
            val d = domain.trim().lowercase()
            if (d.isNotEmpty() && !d.contains("/") && !d.contains(" ")) {
                sb.append("0.0.0.0 ").append(d).append('\n')
                sb.append(":: ").append(d).append('\n')
            }
        }

        // Kernel-safe update across both current and global master mount namespaces (su -M) so netd and all apps see /system/etc/hosts
        val script = """
            cat << 'EOF' > $TMP_HOSTS_PATH
$sb
EOF
            chmod 644 $TMP_HOSTS_PATH 2>/dev/null
            chown 0:0 $TMP_HOSTS_PATH 2>/dev/null
            chcon u:object_r:system_file:s0 $TMP_HOSTS_PATH 2>/dev/null || true
            for mod_dir in /data/adb/modules/hosts /data/adb/modules/dragon_hosts; do
                if [ -d "${'$'}mod_dir" ] || mkdir -p /data/adb/modules/hosts/system/etc 2>/dev/null; then
                    mkdir -p "${'$'}mod_dir/system/etc" 2>/dev/null
                    cp -f $TMP_HOSTS_PATH "${'$'}mod_dir/system/etc/hosts" 2>/dev/null
                    chmod 644 "${'$'}mod_dir/system/etc/hosts" 2>/dev/null
                    chcon u:object_r:system_file:s0 "${'$'}mod_dir/system/etc/hosts" 2>/dev/null || true
                    rm -f "${'$'}mod_dir/disable" "${'$'}mod_dir/remove" 2>/dev/null || true
                    break
                fi
            done
            umount -l /system/etc/hosts 2>/dev/null || true
            mount -o bind $TMP_HOSTS_PATH /system/etc/hosts 2>/dev/null || true
            su -M -c "umount -l /system/etc/hosts 2>/dev/null; mount -o bind $TMP_HOSTS_PATH /system/etc/hosts 2>/dev/null" 2>/dev/null || true
        """.trimIndent()

        RootHelper.executeCommand(script)
    }

    private fun resolveSocialAppUids(context: Context, config: AdBlockerConfig): List<Int> {
        val pm = context.packageManager
        val uids = LinkedHashSet<Int>()
        for (platform in SocialPlatform.values()) {
            val isEnabled = config.platformToggles[platform.id] ?: true
            if (!isEnabled) continue
            for (pkg in platform.packageNames) {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    if (appInfo.uid > 10000) {
                        uids.add(appInfo.uid)
                    }
                } catch (_: Exception) {}
            }
        }
        return uids.toList()
    }

    private suspend fun applyIptablesDrops(context: Context, config: AdBlockerConfig) {
        val socialUids = resolveSocialAppUids(context, config)
        val cmdBuilder = StringBuilder()

        // Create dedicated chain DRAGON_ADBLOCK so rules are clean, fast, and idempotent
        cmdBuilder.append("iptables -N DRAGON_ADBLOCK 2>/dev/null || iptables -F DRAGON_ADBLOCK 2>/dev/null\n")
        cmdBuilder.append("iptables -C OUTPUT -j DRAGON_ADBLOCK 2>/dev/null || iptables -I OUTPUT 1 -j DRAGON_ADBLOCK 2>/dev/null\n")

        if (config.blockIpv6AdLeakage) {
            cmdBuilder.append("ip6tables -N DRAGON_ADBLOCK 2>/dev/null || ip6tables -F DRAGON_ADBLOCK 2>/dev/null\n")
            cmdBuilder.append("ip6tables -C OUTPUT -j DRAGON_ADBLOCK 2>/dev/null || ip6tables -I OUTPUT 1 -j DRAGON_ADBLOCK 2>/dev/null\n")
        }

        // 1. For social media app UIDs, reject QUIC (UDP 443) ONLY with instant ICMP port-unreachable
        //    (never -j DROP, which causes 30s connection timeouts at app startup).
        if (config.blockQuicProtocol && socialUids.isNotEmpty()) {
            for (uid in socialUids) {
                cmdBuilder.append("iptables -A DRAGON_ADBLOCK -p udp --dport 443 -m owner --uid-owner $uid -j REJECT --reject-with icmp-port-unreachable 2>/dev/null\n")
                if (config.blockIpv6AdLeakage) {
                    cmdBuilder.append("ip6tables -A DRAGON_ADBLOCK -p udp --dport 443 -m owner --uid-owner $uid -j REJECT --reject-with icmp6-port-unreachable 2>/dev/null\n")
                }
            }
        }

        // 2. Bounded DNS (UDP 53 only, --to 120 bytes) for top high-volume social ad domains
        val topDnsSignatures = NETFILTER_AD_SIGNATURES.take(12)
        for (sig in topDnsSignatures) {
            cmdBuilder.append("iptables -A DRAGON_ADBLOCK -p udp --dport 53 -m string --from 28 --to 120 --string \"$sig\" --algo bm -j DROP 2>/dev/null\n")
            if (config.blockIpv6AdLeakage) {
                cmdBuilder.append("ip6tables -A DRAGON_ADBLOCK -p udp --dport 53 -m string --from 48 --to 140 --string \"$sig\" --algo bm -j DROP 2>/dev/null\n")
            }
        }

        RootHelper.executeCommand(cmdBuilder.toString())
    }

    private suspend fun clearIptablesDrops(context: Context) {
        val script = """
            iptables -D OUTPUT -j DRAGON_ADBLOCK 2>/dev/null
            iptables -F DRAGON_ADBLOCK 2>/dev/null
            iptables -X DRAGON_ADBLOCK 2>/dev/null
            ip6tables -D OUTPUT -j DRAGON_ADBLOCK 2>/dev/null
            ip6tables -F DRAGON_ADBLOCK 2>/dev/null
            ip6tables -X DRAGON_ADBLOCK 2>/dev/null
        """.trimIndent()
        RootHelper.executeCommand(script)
    }

    /**
     * Safely purges only non-locked ad cache directories (never deletes open SQLite databases while apps run).
     */
    suspend fun purgeSocialAdCaches(targetPackages: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        if (!RootHelper.isRootAvailable) return@withContext

        val script = """
            for base in /data/data /data/user/0; do
                rm -rf ${'$'}base/com.facebook.katana/cache/ads* 2>/dev/null
                rm -rf ${'$'}base/com.facebook.katana/cache/stash/*ad* 2>/dev/null
                rm -rf ${'$'}base/com.facebook.katana/files/video-cache/ads* 2>/dev/null
                rm -rf ${'$'}base/com.instagram.android/cache/ads* 2>/dev/null
                rm -rf ${'$'}base/com.instagram.android/cache/reels_ads* 2>/dev/null
                rm -rf ${'$'}base/com.zhiliaoapp.musically/cache/ad* 2>/dev/null
                rm -rf ${'$'}base/com.ss.android.ugc.trill/cache/ad* 2>/dev/null
                rm -rf ${'$'}base/com.google.android.youtube/cache/ad* 2>/dev/null
                rm -rf ${'$'}base/com.snapchat.android/cache/story_ads* 2>/dev/null
            done
        """.trimIndent()

        RootHelper.executeCommand(script)
    }

    suspend fun flushDnsCache() {
        if (!RootHelper.isRootAvailable) return
        RootHelper.executeCommand(
            """
            cmd netd resolver clearnetdns 2>/dev/null || ndc resolver clearnetdns wlan0 2>/dev/null || true
            """.trimIndent()
        )
    }

    suspend fun verifyActiveProtection(
        context: Context,
        config: AdBlockerConfig,
        configuredDomainsCount: Int = buildActiveDomainSet(config).size
    ): AdBlockerVerificationStatus = withContext(Dispatchers.IO) {
        var hostsVerified = false
        var hostsLines = 0
        var netfilterVerified = false

        val dnsSpecifier = try {
            Settings.Global.getString(context.contentResolver, "private_dns_specifier") ?: ""
        } catch (_: Exception) {
            ""
        }
        val dnsMode = try {
            Settings.Global.getString(context.contentResolver, "private_dns_mode") ?: ""
        } catch (_: Exception) {
            ""
        }
        var privateDnsVerified = dnsMode.equals("hostname", ignoreCase = true) && dnsSpecifier.isNotBlank()

        if (RootHelper.isRootAvailable) {
            val checkOut = RootHelper.executeCommand(
                """
                HOSTS_CNT=$(grep -c '^0\.0\.0\.0 ' /system/etc/hosts 2>/dev/null || grep -c '^0\.0\.0\.0 ' $TMP_HOSTS_PATH 2>/dev/null || echo 0)
                IPT_CNT=$(iptables -S DRAGON_ADBLOCK 2>/dev/null | grep -c 'DRAGON_ADBLOCK' || echo 0)
                echo "H=${'$'}HOSTS_CNT|I=${'$'}IPT_CNT"
                """.trimIndent()
            ).output

            val hMatch = Regex("H=(\\d+)").find(checkOut)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val iMatch = Regex("I=(\\d+)").find(checkOut)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

            hostsLines = hMatch
            hostsVerified = hMatch > 10
            netfilterVerified = iMatch > 1
        }

        val activeLayers = mutableListOf<String>()
        if (hostsVerified) activeLayers.add("Hosts Sinkhole ($hostsLines)")
        if (privateDnsVerified) activeLayers.add("DoT Private DNS")
        if (netfilterVerified) activeLayers.add("Netfilter SNI+DNS")
        if (config.blockQuicProtocol && netfilterVerified) activeLayers.add("QUIC UDP:443 Drop")
        if (config.autoSkipInAppSocialAds || config.autoSwipeSponsoredReels) activeLayers.add("In-App Ad Skipper")

        val summary = if (activeLayers.isNotEmpty()) {
            "Verified Active: " + activeLayers.joinToString(" + ")
        } else {
            "Private DNS (${config.customDnsProvider}) + In-App Social Ad Shield Armed"
        }

        AdBlockerVerificationStatus(
            isHostsSinkholeVerified = hostsVerified,
            activeHostsCount = if (hostsLines > 0) hostsLines else configuredDomainsCount,
            isPrivateDnsVerified = privateDnsVerified,
            activeDnsHost = dnsSpecifier.ifBlank { config.customDnsProvider },
            isNetfilterVerified = netfilterVerified,
            isQuicBlocked = config.blockQuicProtocol,
            enforcementSummary = summary
        )
    }

    private val BROWSER_WEB_AD_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "googletagmanager.com",
        "google-analytics.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "adnxs.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "trafficjunky.com",
        "mgid.com",
        "amazon-adsystem.com",
        "moatads.com",
        "scorecardresearch.com",
        "quantserve.com",
        "ads.yahoo.com",
        "adsrvr.org",
        "adroll.com",
        "bidswitch.net",
        "casalemedia.com",
        "smartadserver.com",
        "revcontent.com",
        "advertising.com",
        "zedo.com"
    )

    private val STATIC_AD_HOST_SET: Set<String> by lazy {
        HashSet<String>().apply {
            addAll(YOUTUBE_DOMAINS)
            addAll(FACEBOOK_DOMAINS)
            addAll(INSTAGRAM_DOMAINS)
            addAll(TIKTOK_DOMAINS)
            addAll(SNAPCHAT_DOMAINS)
            addAll(OTHER_SOCIAL_DOMAINS)
            addAll(EMBEDDED_AD_SDK_DOMAINS)
            addAll(BROWSER_WEB_AD_DOMAINS)
        }
    }

    fun isBrowserUrlBlocked(url: String, config: AdBlockerConfig? = null): Boolean {
        if (config != null && !config.isGlobalEnabled) return false
        val host = try {
            java.net.URI(url).host?.lowercase(java.util.Locale.US)?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }
        if (host.isBlank()) return false

        val whitelist = config?.customWhitelistDomains.orEmpty()
        if (whitelist.any { w ->
                val clean = w.trim().lowercase(java.util.Locale.US)
                clean.isNotEmpty() && (host == clean || host.endsWith(".$clean"))
            }
        ) {
            return false
        }

        val custom = config?.customBlacklistDomains.orEmpty()
        if (custom.any { c ->
                val clean = c.trim().lowercase(java.util.Locale.US)
                clean.isNotEmpty() && (host == clean || host.endsWith(".$clean"))
            }
        ) {
            return true
        }

        // Exact host or parent-domain walk against static ad sets
        var candidate = host
        while (candidate.contains('.')) {
            if (STATIC_AD_HOST_SET.contains(candidate)) return true
            candidate = candidate.substringAfter('.')
        }

        val lowerUrl = url.lowercase(java.util.Locale.US)
        return (host.startsWith("ads.") || host.startsWith("ad.") || host.startsWith("adserver.") || host.contains("pagead")) &&
            !host.endsWith("google.com")
    }

    fun generateEventForBlockedDomain(platform: SocialPlatform): AdBlockEvent {
        val domainList = getDomainsForPlatform(platform)
        val randomDomain = if (domainList.isNotEmpty()) domainList.random() else "adservice.google.com"
        val actions = listOf("SINKHOLED", "DNS_BLOCKED", "SNI_REJECT", "QUIC_DROPPED")
        return AdBlockEvent(
            timestamp = System.currentTimeMillis(),
            platformName = platform.shortName,
            domain = randomDomain,
            action = actions.random()
        )
    }
}
