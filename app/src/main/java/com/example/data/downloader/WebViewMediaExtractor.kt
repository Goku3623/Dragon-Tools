package com.example.data.downloader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.MediaPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class WebViewExtractedMedia(
    val title: String?,
    val creator: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val videoUrls: List<String>,
    val audioUrls: List<String>,
    val cookies: String?,
    val fileUrls: List<String> = emptyList()
)

/**
 * Manages authenticated platform sessions, age-gate/consent cookies, custom Cookie headers,
 * and headless WebView DOM/Network media stream interception for private or restricted content.
 */
class WebViewMediaExtractor(private val context: Context) {

    private val prefs = context.getSharedPreferences("dragon_downloader_auth", Context.MODE_PRIVATE)
    private val customCookiesByDomain = ConcurrentHashMap<String, String>()

    private val _restrictedBypassEnabled = MutableStateFlow(prefs.getBoolean("restricted_bypass_enabled", true))
    val restrictedBypassEnabled: StateFlow<Boolean> = _restrictedBypassEnabled.asStateFlow()

    private val _sessionUpdateCounter = MutableStateFlow(0)
    val sessionUpdateCounter: StateFlow<Int> = _sessionUpdateCounter.asStateFlow()

    @Volatile
    private var consentCookiesSeeded = false

    init {
        loadSavedCookies()
    }

    fun setRestrictedBypassEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("restricted_bypass_enabled", enabled).apply()
        _restrictedBypassEnabled.value = enabled
        if (enabled) {
            seedConsentAndAgeGateCookies()
        }
        _sessionUpdateCounter.value += 1
    }

    private fun ensureConsentCookiesSeeded() {
        if (consentCookiesSeeded) return
        consentCookiesSeeded = true
        seedConsentAndAgeGateCookies()
    }

    private fun seedConsentAndAgeGateCookies() {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            // YouTube / Google consent & age-gate cookies
            cm.setCookie("https://www.youtube.com", "CONSENT=YES+cb.20240101-17-p0.en+FX+999; Domain=.youtube.com; Path=/; Secure")
            cm.setCookie("https://www.youtube.com", "PREF=f6=40000000&hl=en; Domain=.youtube.com; Path=/; Secure")
            // Reddit 18+ NSFW / Restricted gate bypass cookie
            cm.setCookie("https://www.reddit.com", "over18=1; Domain=.reddit.com; Path=/; Secure")
            // Instagram cookie consent
            cm.setCookie("https://www.instagram.com", "ig_cb=1; Domain=.instagram.com; Path=/; Secure")
            cm.flush()
        } catch (_: Throwable) {}
    }

    fun notifyWebViewLoginCompleted() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {}
        _sessionUpdateCounter.value += 1
    }

    fun hasAuthenticatedSession(platform: MediaPlatform): Boolean {
        val checkUrl = when (platform) {
            MediaPlatform.YOUTUBE, MediaPlatform.YOUTUBE_SHORTS -> "https://www.youtube.com"
            MediaPlatform.INSTAGRAM -> "https://www.instagram.com"
            MediaPlatform.FACEBOOK -> "https://www.facebook.com"
            MediaPlatform.TIKTOK -> "https://www.tiktok.com"
            MediaPlatform.TWITTER -> "https://x.com"
            MediaPlatform.REDDIT -> "https://www.reddit.com"
            MediaPlatform.SNAPCHAT -> "https://www.snapchat.com"
            MediaPlatform.PINTEREST -> "https://www.pinterest.com"
            MediaPlatform.VIMEO -> "https://vimeo.com"
            MediaPlatform.PATREON -> "https://www.patreon.com"
            MediaPlatform.BILIBILI -> "https://www.bilibili.tv"
            MediaPlatform.PAWCHIVE -> "https://pawchive.pw"
            else -> return false
        }
        val cookieStr = getCookiesForUrl(checkUrl) ?: return false
        return when (platform) {
            MediaPlatform.YOUTUBE, MediaPlatform.YOUTUBE_SHORTS ->
                cookieStr.contains("SAPISID=") || cookieStr.contains("SSID=") || cookieStr.contains("__Secure-3PSID=")
            MediaPlatform.INSTAGRAM ->
                cookieStr.contains("sessionid=") || cookieStr.contains("ds_user_id=")
            MediaPlatform.FACEBOOK ->
                cookieStr.contains("c_user=") || cookieStr.contains("xs=")
            MediaPlatform.TIKTOK ->
                cookieStr.contains("sessionid=") || cookieStr.contains("sid_tt=")
            MediaPlatform.TWITTER ->
                cookieStr.contains("auth_token=") || cookieStr.contains("ct0=")
            MediaPlatform.REDDIT ->
                cookieStr.contains("reddit_session=") || cookieStr.contains("token_v2=")
            MediaPlatform.PATREON ->
                cookieStr.contains("session_id=")
            MediaPlatform.BILIBILI ->
                cookieStr.contains("SESSDATA=") || cookieStr.contains("bili_jct=") || cookieStr.length > 25
            MediaPlatform.PAWCHIVE ->
                cookieStr.contains("session") || cookieStr.contains("token") || cookieStr.contains("cf_clearance") || cookieStr.length > 20
            else -> cookieStr.length > 30
        }
    }

    fun getCookiesForUrl(url: String): String? {
        ensureConsentCookiesSeeded()
        val webKitCookies = try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Throwable) {
            null
        }
        val host = try {
            java.net.URI(url).host?.lowercase(Locale.US)?.removePrefix("www.")?.removePrefix("m.") ?: ""
        } catch (_: Exception) {
            ""
        }
        val customCookie = customCookiesByDomain.entries.firstOrNull { (domain, _) ->
            host == domain || host.endsWith(".$domain")
        }?.value

        val defaultGateCookie = if (_restrictedBypassEnabled.value) {
            when {
                host.contains("youtube.com") || host.contains("youtu.be") ->
                    "CONSENT=YES+cb.20240101-17-p0.en+FX+999; PREF=f6=40000000&hl=en"
                host.contains("reddit.com") || host.contains("redd.it") ->
                    "over18=1"
                host.contains("instagram.com") ->
                    "ig_cb=1"
                else -> null
            }
        } else null

        val parts = mutableListOf<String>()
        if (!defaultGateCookie.isNullOrBlank()) parts.add(defaultGateCookie)
        if (!webKitCookies.isNullOrBlank()) parts.add(webKitCookies)
        if (!customCookie.isNullOrBlank()) parts.add(customCookie)

        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    fun setCustomCookieForDomain(domain: String, cookieString: String) {
        val cleanDomain = domain.trim().lowercase(Locale.US)
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore("/")
        if (cleanDomain.isBlank()) return
        if (cookieString.isBlank()) {
            customCookiesByDomain.remove(cleanDomain)
        } else {
            customCookiesByDomain[cleanDomain] = cookieString.trim()
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cookieString.split(";").forEach { pair ->
                    val trimmed = pair.trim()
                    if (trimmed.isNotEmpty()) {
                        cm.setCookie("https://$cleanDomain", "$trimmed; Domain=.$cleanDomain; Path=/; Secure")
                    }
                }
                cm.flush()
            } catch (_: Exception) {}
        }
        saveCookies()
        _sessionUpdateCounter.value += 1
    }

    fun getAllCustomCookies(): Map<String, String> = customCookiesByDomain.toMap()

    fun clearAllSessionData() {
        customCookiesByDomain.clear()
        prefs.edit().remove("custom_cookies_json").apply()
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (_: Exception) {}
        seedConsentAndAgeGateCookies()
        _sessionUpdateCounter.value += 1
    }

    private fun saveCookies() {
        try {
            val json = JSONObject()
            customCookiesByDomain.forEach { (k, v) -> json.put(k, v) }
            prefs.edit().putString("custom_cookies_json", json.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadSavedCookies() {
        try {
            val raw = prefs.getString("custom_cookies_json", null) ?: return
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k, "")
                if (v.isNotBlank()) customCookiesByDomain[k] = v
            }
        } catch (_: Exception) {}
    }

    /**
     * Loads the target or embed URL in an offscreen WebView with cookies enabled,
     * intercepting network requests for direct video/audio streams and querying the DOM.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractViaWebView(targetUrl: String, timeoutMs: Long = 10000L): WebViewExtractedMedia? {
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    var webView: WebView? = null
                    val interceptedVideos = LinkedHashSet<String>()
                    val interceptedAudios = LinkedHashSet<String>()
                    val interceptedFiles = LinkedHashSet<String>()
                    var completed = false

                    fun finish(title: String?, creator: String?, thumb: String?, durationSec: Long) {
                        if (completed) return
                        completed = true
                        val cookies = getCookiesForUrl(targetUrl)
                        try {
                            webView?.stopLoading()
                            webView?.destroy()
                        } catch (_: Exception) {}
                        if (cont.isActive) {
                            cont.resume(
                                WebViewExtractedMedia(
                                    title = title,
                                    creator = creator,
                                    thumbnailUrl = thumb,
                                    durationSeconds = durationSec,
                                    videoUrls = interceptedVideos.toList(),
                                    audioUrls = interceptedAudios.toList(),
                                    cookies = cookies,
                                    fileUrls = interceptedFiles.toList()
                                )
                            )
                        }
                    }

                    try {
                        ensureConsentCookiesSeeded()
                        val wv = WebView(context.applicationContext)
                        webView = wv
                        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            runCatching {
                                wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
                            }
                        }
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(wv, true)

                        wv.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = MediaExtractionEngine.MOBILE_USER_AGENT
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?
                            ): Boolean {
                                finish(null, null, null, 0L)
                                return true
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val rawUrl = request?.url?.toString() ?: return null
                                val reqUrl = normalizeInterceptedStreamUrl(rawUrl)
                                val lower = reqUrl.lowercase(Locale.US)
                                if (isLikelyAudioStreamUrl(lower)) {
                                    synchronized(interceptedAudios) {
                                        interceptedAudios.add(reqUrl)
                                    }
                                } else if (isLikelyVideoStreamUrl(lower)) {
                                    synchronized(interceptedVideos) {
                                        interceptedVideos.add(reqUrl)
                                    }
                                } else if (isLikelyDownloadableFileUrl(lower)) {
                                    synchronized(interceptedFiles) {
                                        interceptedFiles.add(reqUrl)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                fun pollDomAndFinish(attempt: Int) {
                                    if (completed) return
                                    val js = """
                                        (function() {
                                            var videos = Array.from(document.querySelectorAll('video'));
                                            var bestV = null;
                                            var dur = 0;
                                            for (var k = 0; k < videos.length; k++) {
                                                var cand = videos[k];
                                                var d = (cand && cand.duration && isFinite(cand.duration)) ? Math.round(cand.duration) : 0;
                                                if (!bestV || d > dur) {
                                                    bestV = cand;
                                                    dur = d;
                                                }
                                            }
                                            var vSrc = bestV ? (bestV.currentSrc || bestV.src || '') : '';
                                            var sources = Array.from(document.querySelectorAll('video source')).map(function(s){ return s.src; }).filter(Boolean);
                                            var domLinks = Array.from(document.querySelectorAll('a[href], [data-src], [data-video], [data-url]')).map(function(el){
                                                return el.href || el.getAttribute('data-src') || el.getAttribute('data-video') || el.getAttribute('data-url') || '';
                                            }).filter(function(u){
                                                return /\.(mp4|m4v|mov|webm|mkv|m3u8)(\?|$)/i.test(u);
                                            });
                                            sources = sources.concat(domLinks);
                                            var ogVideo = document.querySelector('meta[property="og:video"], meta[property="og:video:secure_url"]');
                                            var ogTitle = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
                                            var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
                                            var ogAuthor = document.querySelector('meta[property="og:site_name"], meta[name="author"]');
                                            var ytVideos = [];
                                            var ytAudios = [];
                                            var patreonMuxUrls = [];
                                            try {
                                                var pr = window.ytInitialPlayerResponse;
                                                if (pr) {
                                                    if (!dur && pr.videoDetails && pr.videoDetails.lengthSeconds) {
                                                        dur = parseInt(pr.videoDetails.lengthSeconds, 10) || 0;
                                                    }
                                                    if (pr.streamingData) {
                                                        var fmts = (pr.streamingData.formats || []).concat(pr.streamingData.adaptiveFormats || []);
                                                        for (var i = 0; i < fmts.length; i++) {
                                                            var f = fmts[i];
                                                            if (f && f.url) {
                                                                var mt = (f.mimeType || '').toLowerCase();
                                                                if (mt.indexOf('audio/') === 0) {
                                                                    ytAudios.push(f.url);
                                                                } else {
                                                                    ytVideos.push(f.url);
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch(e) {}
                                            try {
                                                var ndEl = document.getElementById('__NEXT_DATA__');
                                                if (ndEl && ndEl.textContent) {
                                                    var nd = JSON.parse(ndEl.textContent);
                                                    var env = nd && nd.props && nd.props.pageProps && nd.props.pageProps.bootstrapEnvelope;
                                                    var postObj = env && ((env.pageBootstrap && env.pageBootstrap.post) || (env.bootstrap && env.bootstrap.post) || env.post);
                                                    if (postObj) {
                                                        var pData = postObj.data || postObj;
                                                        var pAttr = pData.attributes || pData;
                                                        if (pAttr && pAttr.current_user_can_view !== false) {
                                                            if (pAttr.post_file) {
                                                                if (!dur && pAttr.post_file.duration) {
                                                                    dur = Math.round(Number(pAttr.post_file.duration)) || 0;
                                                                }
                                                                if (pAttr.post_file.download_url) patreonMuxUrls.push(pAttr.post_file.download_url);
                                                                if (pAttr.post_file.url) patreonMuxUrls.push(pAttr.post_file.url);
                                                            }
                                                            var walkPost = function(obj) {
                                                                if (!obj || typeof obj !== 'object') return;
                                                                if (obj.mux_playback_id) {
                                                                    var tok = obj.mux_token || obj.playback_token || obj.token || '';
                                                                    patreonMuxUrls.push('https://stream.mux.com/' + obj.mux_playback_id + '.m3u8' + (tok ? ('?token=' + encodeURIComponent(tok)) : ''));
                                                                }
                                                                var keys = Object.keys(obj);
                                                                for (var x = 0; x < keys.length; x++) {
                                                                    var ky = keys[x];
                                                                    var kl = ky.toLowerCase();
                                                                    if (kl.indexOf('preview') >= 0 || kl.indexOf('teaser') >= 0 || kl.indexOf('trailer') >= 0) continue;
                                                                    walkPost(obj[ky]);
                                                                }
                                                            };
                                                            if (pAttr.post_metadata) walkPost(pAttr.post_metadata);
                                                            var inc = postObj.included || [];
                                                            for (var m = 0; m < inc.length; m++) {
                                                                var it = inc[m];
                                                                if (it && it.type === 'media' && it.attributes && !it.attributes.is_preview) {
                                                                    var orel = (it.attributes.owner_relationship || '').toLowerCase();
                                                                    if (orel.indexOf('preview') >= 0 || orel.indexOf('teaser') >= 0) continue;
                                                                    if (it.attributes.download_url) patreonMuxUrls.push(it.attributes.download_url);
                                                                    if (it.attributes.metadata) {
                                                                        var md = it.attributes.metadata;
                                                                        var mDur = Number(md.duration || (md.video_metadata && md.video_metadata.duration) || 0);
                                                                        if (mDur > dur) dur = Math.round(mDur);
                                                                        walkPost(md);
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch(e) {}
                                            var fileLinks = Array.from(document.querySelectorAll('a[href]')).filter(function(el) {
                                                var href = el.href || '';
                                                if (!href || !href.startsWith('http')) return false;
                                                if (el.hasAttribute('download')) return true;
                                                return /\.(zip|rar|7z|tar|gz|tgz|bz2|xz|zst|apk|apks|xapk|obb|exe|msi|dmg|pkg|deb|rpm|appimage|iso|img|bin|pdf|doc|docx|xls|xlsx|ppt|pptx|txt|csv|rtf|epub|mobi|torrent|jar|wasm|ttf|otf|woff2)(\?|$)/i.test(href);
                                            }).map(function(el) { return el.href; });
                                            return JSON.stringify({
                                                title: (ogTitle ? ogTitle.content : document.title) || '',
                                                creator: (ogAuthor ? ogAuthor.content : '') || '',
                                                thumb: (ogImg ? ogImg.content : (bestV ? bestV.poster : '')) || '',
                                                duration: dur,
                                                videoSrc: vSrc,
                                                sources: sources,
                                                ogVideo: ogVideo ? ogVideo.content : '',
                                                ytVideos: ytVideos,
                                                ytAudios: ytAudios,
                                                patreonMuxUrls: patreonMuxUrls,
                                                fileLinks: fileLinks
                                            });
                                        })();
                                    """.trimIndent()

                                    view?.evaluateJavascript(js) { resultJson ->
                                        if (completed) return@evaluateJavascript
                                        try {
                                            val unquoted = if (resultJson != null && resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                                                org.json.JSONTokener(resultJson).nextValue().toString()
                                            } else {
                                                resultJson ?: "{}"
                                            }
                                            val obj = JSONObject(unquoted)
                                            val vSrc = obj.optString("videoSrc", "")
                                            if (vSrc.startsWith("http") && !isHtmlEmbedPageUrl(vSrc)) {
                                                interceptedVideos.add(normalizeInterceptedStreamUrl(vSrc))
                                            }
                                            val ogV = obj.optString("ogVideo", "")
                                            if (ogV.startsWith("http") && !isHtmlEmbedPageUrl(ogV) && isLikelyVideoStreamUrl(ogV.lowercase(Locale.US))) {
                                                interceptedVideos.add(normalizeInterceptedStreamUrl(ogV))
                                            }
                                            val arr = obj.optJSONArray("sources")
                                            if (arr != null) {
                                                for (i in 0 until arr.length()) {
                                                    val s = arr.optString(i, "")
                                                    if (s.startsWith("http") && !isHtmlEmbedPageUrl(s)) {
                                                        interceptedVideos.add(normalizeInterceptedStreamUrl(s))
                                                    }
                                                }
                                            }
                                            val pmArr = obj.optJSONArray("patreonMuxUrls")
                                            if (pmArr != null) {
                                                for (i in 0 until pmArr.length()) {
                                                    val s = pmArr.optString(i, "")
                                                    if (s.startsWith("http") && !isPreviewUrl(s)) {
                                                        interceptedVideos.add(normalizeInterceptedStreamUrl(s))
                                                    }
                                                }
                                            }
                                            val ytV = obj.optJSONArray("ytVideos")
                                            if (ytV != null) {
                                                for (i in 0 until ytV.length()) {
                                                    val s = ytV.optString(i, "")
                                                    if (s.startsWith("http")) interceptedVideos.add(normalizeInterceptedStreamUrl(s))
                                                }
                                            }
                                            val ytA = obj.optJSONArray("ytAudios")
                                            if (ytA != null) {
                                                for (i in 0 until ytA.length()) {
                                                    val s = ytA.optString(i, "")
                                                    if (s.startsWith("http")) interceptedAudios.add(normalizeInterceptedStreamUrl(s))
                                                }
                                            }
                                            val flArr = obj.optJSONArray("fileLinks")
                                            if (flArr != null) {
                                                for (i in 0 until flArr.length()) {
                                                    val s = flArr.optString(i, "")
                                                    if (s.startsWith("http")) {
                                                        synchronized(interceptedFiles) {
                                                            interceptedFiles.add(s)
                                                        }
                                                    }
                                                }
                                            }

                                            val durSec = obj.optLong("duration", 0L)
                                            val hasStreamYet = synchronized(interceptedVideos) { interceptedVideos.isNotEmpty() } ||
                                                synchronized(interceptedAudios) { interceptedAudios.isNotEmpty() } ||
                                                synchronized(interceptedFiles) { interceptedFiles.isNotEmpty() }
                                            if (!hasStreamYet && attempt < 7) {
                                                view.postDelayed({ pollDomAndFinish(attempt + 1) }, 500L)
                                            } else {
                                                finish(
                                                    title = obj.optString("title", "").takeIf { it.isNotBlank() },
                                                    creator = obj.optString("creator", "").takeIf { it.isNotBlank() },
                                                    thumb = obj.optString("thumb", "").takeIf { it.isNotBlank() },
                                                    durationSec = durSec
                                                )
                                            }
                                        } catch (_: Exception) {
                                            finish(view.title, null, null, 0L)
                                        }
                                    }
                                }
                                pollDomAndFinish(0)
                            }
                        }

                        cont.invokeOnCancellation {
                            try {
                                wv.stopLoading()
                                wv.destroy()
                            } catch (_: Exception) {}
                        }

                        val extraHeaders = mutableMapOf<String, String>()
                        getCookiesForUrl(targetUrl)?.let { extraHeaders["Cookie"] = it }
                        wv.loadUrl(targetUrl, extraHeaders)
                    } catch (_: Exception) {
                        finish(null, null, null, 0L)
                    }
                }
            }
        }
    }

    companion object {
        fun normalizeInterceptedStreamUrl(url: String): String {
            if (!url.contains("googlevideo.com/videoplayback", ignoreCase = true)) {
                return url
            }
            return url
                .replace(Regex("([?&])range=[^&]*"), "$1")
                .replace(Regex("([?&])rn=[^&]*"), "$1")
                .replace(Regex("([?&])rbuf=[^&]*"), "$1")
                .replace(Regex("([?&])ump=[^&]*"), "$1")
                .replace(Regex("([?&])srfvp=[^&]*"), "$1")
                .replace("?&", "?")
                .replace(Regex("&+"), "&")
                .trimEnd('&', '?')
        }

        fun isPreviewUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("/preview/") ||
                lower.contains("_preview") ||
                lower.contains("preview=") ||
                lower.contains("teaser") ||
                lower.contains("trailer")
        }

        fun isHtmlEmbedPageUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("/embed/") ||
                lower.contains("youtube.com/v/") ||
                lower.contains("youtube.com/watch") ||
                lower.contains("player.vimeo.com/video/")
        }

        fun isLikelyVideoStreamUrl(lowerUrl: String): Boolean {
            if (lowerUrl.contains(".js") || lowerUrl.contains(".css") || lowerUrl.contains(".jpg") ||
                lowerUrl.contains(".jpeg") || lowerUrl.contains(".png") || lowerUrl.contains(".webp") ||
                lowerUrl.contains(".vtt") || lowerUrl.contains(".srt") || lowerUrl.contains(".ts") ||
                lowerUrl.contains(".m4s") || isPreviewUrl(lowerUrl) || isHtmlEmbedPageUrl(lowerUrl)
            ) {
                return false
            }
            if (lowerUrl.contains("videoplayback?") && lowerUrl.contains("mime=audio")) {
                return false
            }
            return lowerUrl.contains(".mp4") ||
                lowerUrl.contains(".m4v") ||
                lowerUrl.contains(".mov") ||
                lowerUrl.contains(".webm") ||
                lowerUrl.contains(".mkv") ||
                lowerUrl.contains(".m3u8") ||
                lowerUrl.contains("stream.mux.com/") ||
                lowerUrl.contains("patreonusercontent.com/") ||
                lowerUrl.contains("patreon.com/file?") ||
                lowerUrl.contains("bilivideo.com/") ||
                lowerUrl.contains("bilivideo.cn/") ||
                lowerUrl.contains("biliintl.com/") ||
                lowerUrl.contains("pawchive.pw/") ||
                lowerUrl.contains("b-cdn.net/") ||
                lowerUrl.contains("r2.dev/") ||
                lowerUrl.contains("mediadelivery.net/") ||
                lowerUrl.contains("vimeocdn.com/") ||
                lowerUrl.contains("videoplayback?") ||
                (lowerUrl.contains("cdninstagram.com") && (lowerUrl.contains(".mp4") || lowerUrl.contains("bytestart"))) ||
                (lowerUrl.contains("fbcdn.net") && lowerUrl.contains(".mp4")) ||
                (lowerUrl.contains("tiktokcdn") && lowerUrl.contains("video")) ||
                (lowerUrl.contains("video.twimg.com") && lowerUrl.contains(".mp4")) ||
                (lowerUrl.contains("v.redd.it") && lowerUrl.contains("dash_")) ||
                (lowerUrl.contains("sc-cdn.net") && lowerUrl.contains(".mp4")) ||
                (lowerUrl.contains("v.pinimg.com") && lowerUrl.contains(".mp4"))
        }

        fun isLikelyAudioStreamUrl(lowerUrl: String): Boolean {
            return lowerUrl.contains(".m4a") ||
                lowerUrl.contains(".mp3") ||
                lowerUrl.contains(".aac") ||
                lowerUrl.contains(".wav") ||
                lowerUrl.contains(".flac") ||
                lowerUrl.contains(".ogg") ||
                lowerUrl.contains(".opus") ||
                (lowerUrl.contains("videoplayback?") && lowerUrl.contains("mime=audio"))
        }

        fun isLikelyDownloadableFileUrl(lowerUrl: String): Boolean {
            val path = lowerUrl.substringBefore("?").substringBefore("#")
            return MediaPlatform.ARCHIVE_EXTENSIONS.any { path.endsWith(it) } ||
                MediaPlatform.INSTALLER_EXTENSIONS.any { path.endsWith(it) } ||
                MediaPlatform.DISK_IMAGE_AND_BIN_EXTENSIONS.any { path.endsWith(it) } ||
                path.endsWith(".pdf") ||
                path.endsWith(".epub") ||
                path.endsWith(".docx") ||
                path.endsWith(".xlsx") ||
                path.endsWith(".pptx")
        }
    }
}
