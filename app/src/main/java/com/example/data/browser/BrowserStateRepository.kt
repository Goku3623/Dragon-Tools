package com.example.data.browser

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = BrowserStateRepository.DEFAULT_HOME_URL,
    val title: String = "Google",
    val isIncognito: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val progress: Int = 100,
    val isLoading: Boolean = false,
    val desktopMode: Boolean = false
)

data class BrowserHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val visitedAtMs: Long = System.currentTimeMillis()
)

data class BrowserBookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class GrabbedMediaKind(val label: String) {
    VIDEO("VIDEO"),
    AUDIO("AUDIO"),
    FILE("FILE")
}

data class GrabbedMediaCandidate(
    val url: String,
    val title: String,
    val kind: GrabbedMediaKind,
    val containerExt: String,
    val sourceLabel: String
)

/**
 * Manages multi-tab browser state, Google default home URL, incognito tab isolation,
 * persisted browsing history, persisted bookmarks, ad-blocker counters, and page media grabber hits.
 */
class BrowserStateRepository(context: Context) {

    private val prefs = context.getSharedPreferences("dragon_browser_state", Context.MODE_PRIVATE)

    val extensionEngine = BrowserExtensionEngine(context)

    private val _tabsFlow = MutableStateFlow(
        listOf(BrowserTab(url = DEFAULT_HOME_URL, title = "Google"))
    )
    val tabsFlow: StateFlow<List<BrowserTab>> = _tabsFlow.asStateFlow()

    private val _activeTabIdFlow = MutableStateFlow(_tabsFlow.value.first().id)
    val activeTabIdFlow: StateFlow<String> = _activeTabIdFlow.asStateFlow()

    private val _historyFlow = MutableStateFlow<List<BrowserHistoryEntry>>(emptyList())
    val historyFlow: StateFlow<List<BrowserHistoryEntry>> = _historyFlow.asStateFlow()

    private val _bookmarksFlow = MutableStateFlow<List<BrowserBookmark>>(emptyList())
    val bookmarksFlow: StateFlow<List<BrowserBookmark>> = _bookmarksFlow.asStateFlow()

    private val _grabbedMediaByTabFlow = MutableStateFlow<Map<String, List<GrabbedMediaCandidate>>>(emptyMap())
    val grabbedMediaByTabFlow: StateFlow<Map<String, List<GrabbedMediaCandidate>>> = _grabbedMediaByTabFlow.asStateFlow()

    private val _adsBlockedCountFlow = MutableStateFlow(0)
    val adsBlockedCountFlow: StateFlow<Int> = _adsBlockedCountFlow.asStateFlow()

    init {
        loadHistory()
        loadBookmarks()
    }

    fun getActiveTab(): BrowserTab {
        val activeId = _activeTabIdFlow.value
        return _tabsFlow.value.find { it.id == activeId } ?: _tabsFlow.value.first()
    }

    fun openNewTab(
        initialUrl: String = DEFAULT_HOME_URL,
        isIncognito: Boolean = false
    ): BrowserTab {
        val tab = BrowserTab(
            id = UUID.randomUUID().toString(),
            url = initialUrl,
            title = if (isIncognito) "Incognito • Google" else "Google",
            isIncognito = isIncognito
        )
        _tabsFlow.value = _tabsFlow.value + tab
        _activeTabIdFlow.value = tab.id
        return tab
    }

    fun selectTab(tabId: String) {
        if (_tabsFlow.value.any { it.id == tabId }) {
            _activeTabIdFlow.value = tabId
        }
    }

    /**
     * Closes a tab and returns true if any incognito tabs remain open,
     * allowing the UI to clear private WebView storage when the last incognito tab closes.
     */
    fun closeTab(tabId: String): Boolean {
        val current = _tabsFlow.value
        val closingTab = current.find { it.id == tabId }
        val remaining = current.filter { it.id != tabId }

        _grabbedMediaByTabFlow.value = _grabbedMediaByTabFlow.value - tabId

        if (remaining.isEmpty()) {
            val fresh = BrowserTab(url = DEFAULT_HOME_URL, title = "Google", isIncognito = false)
            _tabsFlow.value = listOf(fresh)
            _activeTabIdFlow.value = fresh.id
        } else {
            _tabsFlow.value = remaining
            if (_activeTabIdFlow.value == tabId) {
                _activeTabIdFlow.value = remaining.last().id
            }
        }
        val wasLastIncognito = closingTab?.isIncognito == true && _tabsFlow.value.none { it.isIncognito }
        return wasLastIncognito
    }

    fun closeAllTabs() {
        _grabbedMediaByTabFlow.value = emptyMap()
        val fresh = BrowserTab(url = DEFAULT_HOME_URL, title = "Google", isIncognito = false)
        _tabsFlow.value = listOf(fresh)
        _activeTabIdFlow.value = fresh.id
    }

    fun updateTabState(tabId: String, updater: (BrowserTab) -> BrowserTab) {
        _tabsFlow.value = _tabsFlow.value.map { tab ->
            if (tab.id == tabId) updater(tab) else tab
        }
    }

    fun toggleCurrentTabIncognito(): BrowserTab {
        val cur = getActiveTab()
        return if (cur.isIncognito) {
            // Switch to an existing normal tab or open a fresh normal tab
            val existingNormal = _tabsFlow.value.firstOrNull { !it.isIncognito }
            if (existingNormal != null) {
                _activeTabIdFlow.value = existingNormal.id
                existingNormal
            } else {
                openNewTab(initialUrl = DEFAULT_HOME_URL, isIncognito = false)
            }
        } else {
            openNewTab(initialUrl = DEFAULT_HOME_URL, isIncognito = true)
        }
    }

    fun recordVisit(url: String, title: String?, isIncognito: Boolean) {
        if (isIncognito) return
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return
        }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
            ?: url

        val current = _historyFlow.value
        val latest = current.firstOrNull()
        if (latest != null && latest.url == url && (System.currentTimeMillis() - latest.visitedAtMs) < 5_000L) {
            return
        }

        val entry = BrowserHistoryEntry(
            title = cleanTitle,
            url = url,
            visitedAtMs = System.currentTimeMillis()
        )
        val deduped = current.filterNot { it.url == url && (entry.visitedAtMs - it.visitedAtMs) < 60_000L }
        val updated = (listOf(entry) + deduped).take(200)
        _historyFlow.value = updated
        persistHistory(updated)
    }

    fun deleteHistoryEntry(entryId: String) {
        val updated = _historyFlow.value.filterNot { it.id == entryId }
        _historyFlow.value = updated
        persistHistory(updated)
    }

    fun clearHistory() {
        _historyFlow.value = emptyList()
        prefs.edit().remove("browser_history_v1").apply()
    }

    fun isBookmarked(url: String): Boolean {
        val normalized = url.trim().trimEnd('/')
        return _bookmarksFlow.value.any { it.url.trim().trimEnd('/').equals(normalized, ignoreCase = true) }
    }

    fun toggleBookmark(url: String, title: String?): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val normalized = url.trim().trimEnd('/')
        val existing = _bookmarksFlow.value.find {
            it.url.trim().trimEnd('/').equals(normalized, ignoreCase = true)
        }
        return if (existing != null) {
            val updated = _bookmarksFlow.value.filterNot { it.id == existing.id }
            _bookmarksFlow.value = updated
            persistBookmarks(updated)
            false
        } else {
            val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
                ?: runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
                ?: url
            val updated = listOf(BrowserBookmark(title = cleanTitle, url = url)) + _bookmarksFlow.value
            _bookmarksFlow.value = updated
            persistBookmarks(updated)
            true
        }
    }

    fun removeBookmark(bookmarkId: String) {
        val updated = _bookmarksFlow.value.filterNot { it.id == bookmarkId }
        _bookmarksFlow.value = updated
        persistBookmarks(updated)
    }

    fun recordGrabbedMedia(tabId: String, candidate: GrabbedMediaCandidate) {
        val existing = _grabbedMediaByTabFlow.value[tabId].orEmpty()
        if (existing.any { it.url == candidate.url }) return
        val updated = (listOf(candidate) + existing).take(40)
        _grabbedMediaByTabFlow.value = _grabbedMediaByTabFlow.value + (tabId to updated)
    }

    fun clearGrabbedMediaForTab(tabId: String) {
        _grabbedMediaByTabFlow.value = _grabbedMediaByTabFlow.value - tabId
    }

    fun incrementAdsBlocked() {
        _adsBlockedCountFlow.value += 1
    }

    private fun persistHistory(entries: List<BrowserHistoryEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("url", e.url)
                    put("visitedAtMs", e.visitedAtMs)
                })
            }
            prefs.edit().putString("browser_history_v1", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadHistory() {
        try {
            val raw = prefs.getString("browser_history_v1", null) ?: return
            val arr = JSONArray(raw)
            val list = mutableListOf<BrowserHistoryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "")
                if (url.isBlank()) continue
                list.add(
                    BrowserHistoryEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", url),
                        url = url,
                        visitedAtMs = obj.optLong("visitedAtMs", System.currentTimeMillis())
                    )
                )
            }
            _historyFlow.value = list
        } catch (_: Exception) {}
    }

    private fun persistBookmarks(items: List<BrowserBookmark>) {
        try {
            val arr = JSONArray()
            items.forEach { b ->
                arr.put(JSONObject().apply {
                    put("id", b.id)
                    put("title", b.title)
                    put("url", b.url)
                    put("createdAtMs", b.createdAtMs)
                })
            }
            prefs.edit().putString("browser_bookmarks_v1", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadBookmarks() {
        try {
            if (!prefs.contains("browser_bookmarks_v1")) {
                val defaults = listOf(
                    BrowserBookmark(title = "Google", url = DEFAULT_HOME_URL)
                )
                _bookmarksFlow.value = defaults
                persistBookmarks(defaults)
                return
            }
            val raw = prefs.getString("browser_bookmarks_v1", null) ?: return
            val arr = JSONArray(raw)
            val list = mutableListOf<BrowserBookmark>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "")
                if (url.isBlank()) continue
                list.add(
                    BrowserBookmark(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", url),
                        url = url,
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                    )
                )
            }
            _bookmarksFlow.value = list
        } catch (_: Exception) {}
    }

    companion object {
        const val DEFAULT_HOME_URL = "https://www.google.com"

        /**
         * Resolves omnibox input cleanly:
         * - Blank -> default google.com homepage
         * - Explicit scheme -> direct URL
         * - Domain-like string (no spaces, contains dot) -> https://domain
         * - Anything else -> Google Search query
         */
        fun resolveBrowserInputToUrl(rawInput: String): String {
            val trimmed = rawInput.trim()
            if (trimmed.isBlank()) return DEFAULT_HOME_URL
            val lower = trimmed.lowercase(Locale.US)
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return trimmed
            }
            if (!trimmed.contains(' ') && trimmed.contains('.') && !trimmed.startsWith(".")) {
                return "https://$trimmed"
            }
            val encoded = try {
                URLEncoder.encode(trimmed, "UTF-8")
            } catch (_: Exception) {
                trimmed.replace(" ", "+")
            }
            return "https://www.google.com/search?q=$encoded"
        }
    }
}
