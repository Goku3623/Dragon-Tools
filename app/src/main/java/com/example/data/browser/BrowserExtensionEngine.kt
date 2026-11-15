package com.example.data.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebResourceResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class ExtensionKind(val badge: String) {
    WEB_EXTENSION_MV3("MV3 EXT"),
    WEB_EXTENSION_MV2("MV2 EXT"),
    USERSCRIPT("USERSCRIPT"),
    USERSTYLE("USERSTYLE")
}

data class ExtensionContentScriptRule(
    val matches: List<String> = listOf("<all_urls>"),
    val excludeMatches: List<String> = emptyList(),
    val jsFiles: List<String> = emptyList(),
    val cssFiles: List<String> = emptyList(),
    val inlineJs: String = "",
    val inlineCss: String = "",
    val runAt: String = "document_end", // document_start, document_end, document_idle
    val allFrames: Boolean = false
)

data class ExtensionNetRule(
    val id: Int,
    val actionType: String, // "block" or "redirect"
    val urlFilter: String,
    val redirectUrl: String? = null,
    val initiatorDomains: List<String> = emptyList(),
    val excludedInitiatorDomains: List<String> = emptyList()
)

data class BrowserExtension(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val kind: ExtensionKind,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val sourceFileName: String = "",
    val installDirPath: String = "",
    val popupPath: String? = null,
    val backgroundScriptPath: String? = null,
    val permissions: List<String> = emptyList(),
    val hostPermissions: List<String> = listOf("<all_urls>"),
    val contentScripts: List<ExtensionContentScriptRule> = emptyList(),
    val netRules: List<ExtensionNetRule> = emptyList(),
    val installedAtMs: Long = System.currentTimeMillis()
)

/**
 * Full-featured Browser Extension Runtime Engine for Dragon Browser.
 *
 * Supports importing extensions from local storage via Android SAF (`OpenDocument`):
 * - `.zip`, `.crx` (Chrome Extension CRX2/CRX3 header auto-unwrapped), `.xpi` (Firefox WebExtension)
 * - `.user.js` / `.js` (Tampermonkey / Violentmonkey / Greasemonkey Userscripts)
 * - `.css` / `.user.css` (Stylus UserStyles)
 * - `manifest.json` (Direct unpacked manifest import)
 *
 * Provides real WebView runtime execution:
 * - Content Script & CSS injection (`document_start`, `document_end`, `document_idle`)
 * - URL match pattern engine (`<all_urls>`, domain wildcards, glob & regex patterns)
 * - `chrome.*` / `browser.*` WebExtension API polyfill (`storage.local`, `storage.sync`, `runtime`, `tabs`, `i18n`)
 * - `GM_*` Userscript API polyfill (`GM_getValue`, `GM_setValue`, `GM_addStyle`, cross-origin `GM_xmlhttpRequest`, `GM_openInTab`, `GM_setClipboard`)
 * - `chrome-extension://<extId>/...` and `https://dragon-ext.local/<extId>/...` web-accessible resource serving
 * - `declarative_net_request` network blocking rules in `shouldInterceptRequest`
 * - Interactive `popup.html` extension action modal support
 */
class BrowserExtensionEngine(private val context: Context) {

    private val prefs = context.getSharedPreferences("dragon_browser_extensions_v1", Context.MODE_PRIVATE)
    private val kvStoragePrefs = context.getSharedPreferences("dragon_browser_ext_storage_kv", Context.MODE_PRIVATE)
    private val extensionsRootDir: File = File(context.filesDir, "browser_extensions").apply { mkdirs() }

    private val crossOriginHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _extensionsFlow = MutableStateFlow<List<BrowserExtension>>(emptyList())
    val extensionsFlow: StateFlow<List<BrowserExtension>> = _extensionsFlow.asStateFlow()

    init {
        loadExtensions()
    }

    fun getEnabledExtensionsCount(): Int = _extensionsFlow.value.count { it.enabled }

    fun setExtensionEnabled(extensionId: String, enabled: Boolean) {
        val updated = _extensionsFlow.value.map { ext ->
            if (ext.id == extensionId) ext.copy(enabled = enabled) else ext
        }
        _extensionsFlow.value = updated
        persistExtensions(updated)
    }

    fun removeExtension(extensionId: String): Boolean {
        val target = _extensionsFlow.value.find { it.id == extensionId } ?: return false
        if (target.installDirPath.isNotBlank()) {
            runCatching { File(target.installDirPath).deleteRecursively() }
        }
        kvStoragePrefs.edit().remove("kv_${target.id}").apply()
        val updated = _extensionsFlow.value.filterNot { it.id == extensionId }
        _extensionsFlow.value = updated
        persistExtensions(updated)
        return true
    }

    /**
     * Creates and installs a custom Userscript (JS) or Userstyle (CSS) directly from the UI or tests.
     */
    fun createCustomExtension(
        name: String,
        description: String,
        matchPattern: String,
        jsCode: String,
        cssCode: String,
        runAt: String = "document_end"
    ): BrowserExtension {
        val cleanName = name.trim().ifBlank { "Custom Extension" }
        val extId = "custom_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val extDir = File(extensionsRootDir, extId).apply { mkdirs() }

        val jsFiles = mutableListOf<String>()
        val cssFiles = mutableListOf<String>()
        if (jsCode.isNotBlank()) {
            File(extDir, "content.js").writeText(jsCode, Charsets.UTF_8)
            jsFiles.add("content.js")
        }
        if (cssCode.isNotBlank()) {
            File(extDir, "style.css").writeText(cssCode, Charsets.UTF_8)
            cssFiles.add("style.css")
        }

        val kind = when {
            jsCode.isNotBlank() && cssCode.isNotBlank() -> ExtensionKind.WEB_EXTENSION_MV3
            cssCode.isNotBlank() -> ExtensionKind.USERSTYLE
            else -> ExtensionKind.USERSCRIPT
        }

        val patterns = matchPattern.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("<all_urls>") }

        val extension = BrowserExtension(
            id = extId,
            name = cleanName,
            version = "1.0.0",
            description = description.trim().ifBlank { "Custom browser extension script/style" },
            kind = kind,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = "$cleanName.ext",
            installDirPath = extDir.absolutePath,
            permissions = listOf("storage", "activeTab", "scripting"),
            hostPermissions = patterns,
            contentScripts = listOf(
                ExtensionContentScriptRule(
                    matches = patterns,
                    jsFiles = jsFiles,
                    cssFiles = cssFiles,
                    inlineJs = jsCode,
                    inlineCss = cssCode,
                    runAt = runAt.ifBlank { "document_end" },
                    allFrames = true
                )
            )
        )

        val updated = listOf(extension) + _extensionsFlow.value
        _extensionsFlow.value = updated
        persistExtensions(updated)
        return extension
    }

    /**
     * Imports an extension package or script from a local storage Uri (`content://` or `file://`).
     * Supports `.zip`, `.crx`, `.xpi`, `.user.js`, `.js`, `.css`, and `manifest.json`.
     */
    fun importExtensionFromUri(uri: Uri): Result<BrowserExtension> {
        return runCatching {
            val displayName = resolveDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "extension.zip"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Unable to read selected extension file from storage.")
            importExtensionFromBytes(displayName, bytes).getOrThrow()
        }
    }

    fun importExtensionFromBytes(fileName: String, rawBytes: ByteArray): Result<BrowserExtension> {
        return runCatching {
            require(rawBytes.isNotEmpty()) { "Selected extension file is empty." }
            val lowerName = fileName.lowercase(Locale.US).trim()

            // Check if the payload is a CRX or ZIP/XPI archive by magic bytes or extension
            val zipOffset = findZipHeaderOffset(rawBytes)
            val isArchive = zipOffset >= 0 && (
                lowerName.endsWith(".zip") ||
                    lowerName.endsWith(".crx") ||
                    lowerName.endsWith(".xpi") ||
                    zipOffset == 0 ||
                    isCrxMagic(rawBytes)
                )

            val installed = when {
                isArchive -> installArchiveExtension(fileName, rawBytes, zipOffset)
                lowerName.endsWith(".css") -> installUserStyleExtension(fileName, rawBytes.toString(Charsets.UTF_8))
                lowerName.endsWith(".json") -> installDirectManifestJsonExtension(fileName, rawBytes.toString(Charsets.UTF_8))
                else -> {
                    val text = rawBytes.toString(Charsets.UTF_8)
                    val trimmed = text.trimStart()
                    if (trimmed.startsWith("{") && trimmed.contains("\"manifest_version\"")) {
                        installDirectManifestJsonExtension(fileName, text)
                    } else if (trimmed.startsWith("/* ==UserStyle==", ignoreCase = true)) {
                        installUserStyleExtension(fileName, text)
                    } else {
                        installUserScriptExtension(fileName, text)
                    }
                }
            }

            // Replace if same name/source already imported or prepend
            val filtered = _extensionsFlow.value.filterNot {
                !it.isBuiltIn && it.name.equals(installed.name, ignoreCase = true) && it.sourceFileName == installed.sourceFileName
            }
            val updated = listOf(installed) + filtered
            _extensionsFlow.value = updated
            persistExtensions(updated)
            installed
        }
    }

    private fun installArchiveExtension(
        fileName: String,
        rawBytes: ByteArray,
        zipStartOffset: Int
    ): BrowserExtension {
        val extId = "ext_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val extractRoot = File(extensionsRootDir, extId).apply { mkdirs() }

        var extractedCount = 0
        ZipInputStream(ByteArrayInputStream(rawBytes, zipStartOffset, rawBytes.size - zipStartOffset)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val safeRelPath = entry.name
                    .replace('\\', '/')
                    .split('/')
                    .filter { it.isNotBlank() && it != "." && it != ".." }
                    .joinToString("/")

                if (safeRelPath.isNotBlank()) {
                    val outFile = File(extractRoot, safeRelPath)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            val buf = ByteArray(8192)
                            var read: Int
                            while (zis.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                            }
                        }
                        extractedCount++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (extractedCount == 0) {
            extractRoot.deleteRecursively()
            throw IllegalArgumentException("Archive contains no valid extension files.")
        }

        // Find manifest.json (at root or inside a single top-level directory)
        val manifestFile = findManifestFile(extractRoot)
        if (manifestFile != null) {
            val baseDir = manifestFile.parentFile ?: extractRoot
            return parseWebExtensionManifest(
                extId = extId,
                sourceFileName = fileName,
                baseDir = baseDir,
                manifestJsonText = manifestFile.readText(Charsets.UTF_8)
            )
        }

        // Fallback: if archive contains .user.js / .js / .css files without manifest.json, bundle them automatically!
        val allFiles = extractRoot.walkTopDown().filter { it.isFile }.toList()
        val jsFiles = allFiles.filter { it.name.endsWith(".js", ignoreCase = true) }
            .map { it.relativeTo(extractRoot).path.replace('\\', '/') }
        val cssFiles = allFiles.filter { it.name.endsWith(".css", ignoreCase = true) }
            .map { it.relativeTo(extractRoot).path.replace('\\', '/') }

        if (jsFiles.isEmpty() && cssFiles.isEmpty()) {
            extractRoot.deleteRecursively()
            throw IllegalArgumentException("No manifest.json, .js, or .css found in extension archive.")
        }

        val cleanTitle = fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
            .ifBlank { "Imported Archive Extension" }

        return BrowserExtension(
            id = extId,
            name = cleanTitle,
            version = "1.0.0",
            description = "Imported archive with ${jsFiles.size} script(s) and ${cssFiles.size} style(s)",
            kind = ExtensionKind.WEB_EXTENSION_MV3,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = fileName,
            installDirPath = extractRoot.absolutePath,
            permissions = listOf("storage", "activeTab", "scripting"),
            hostPermissions = listOf("<all_urls>"),
            contentScripts = listOf(
                ExtensionContentScriptRule(
                    matches = listOf("<all_urls>"),
                    jsFiles = jsFiles,
                    cssFiles = cssFiles,
                    runAt = "document_end",
                    allFrames = true
                )
            )
        )
    }

    private fun parseWebExtensionManifest(
        extId: String,
        sourceFileName: String,
        baseDir: File,
        manifestJsonText: String
    ): BrowserExtension {
        val manifest = JSONObject(manifestJsonText)
        val defaultLocale = manifest.optString("default_locale", "en").ifBlank { "en" }
        val i18nMap = loadLocaleMessages(baseDir, defaultLocale)

        val rawName = manifest.optString("name", "").ifBlank {
            sourceFileName.substringBeforeLast('.')
        }
        val resolvedName = resolveI18nPlaceholders(rawName, i18nMap)
            .ifBlank { sourceFileName.substringBeforeLast('.') }

        val version = manifest.optString("version", "1.0.0").ifBlank { "1.0.0" }
        val rawDesc = manifest.optString("description", "")
        val resolvedDesc = resolveI18nPlaceholders(rawDesc, i18nMap)
            .ifBlank { "WebExtension package ($sourceFileName)" }

        val manifestVersion = manifest.optInt("manifest_version", 3)
        val kind = if (manifestVersion == 2) ExtensionKind.WEB_EXTENSION_MV2 else ExtensionKind.WEB_EXTENSION_MV3

        val permissions = mutableListOf<String>()
        manifest.optJSONArray("permissions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "").trim()
                if (p.isNotBlank()) permissions.add(p)
            }
        }

        val hostPermissions = mutableListOf<String>()
        manifest.optJSONArray("host_permissions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val hp = arr.optString(i, "").trim()
                if (hp.isNotBlank()) hostPermissions.add(hp)
            }
        }
        // In MV2, host patterns can be inside `permissions`
        permissions.filter { it.contains("://") || it == "<all_urls>" }.forEach {
            if (!hostPermissions.contains(it)) hostPermissions.add(it)
        }
        if (hostPermissions.isEmpty()) {
            hostPermissions.add("<all_urls>")
        }

        // Parse popup (MV3 action / MV2 browser_action / page_action)
        val popupPath = (
            manifest.optJSONObject("action")?.optString("default_popup")
                ?: manifest.optJSONObject("browser_action")?.optString("default_popup")
                ?: manifest.optJSONObject("page_action")?.optString("default_popup")
            )?.trim()?.removePrefix("/")?.takeIf { it.isNotBlank() && File(baseDir, it).exists() }

        // Parse background script / service_worker
        val bgObj = manifest.optJSONObject("background")
        val backgroundScriptPath = when {
            bgObj == null -> null
            bgObj.has("service_worker") -> bgObj.optString("service_worker", "").trim().removePrefix("/")
            bgObj.has("scripts") -> bgObj.optJSONArray("scripts")?.optString(0, "")?.trim()?.removePrefix("/")
            else -> null
        }?.takeIf { it.isNotBlank() && File(baseDir, it).exists() }

        // Parse content_scripts
        val contentRules = mutableListOf<ExtensionContentScriptRule>()
        manifest.optJSONArray("content_scripts")?.let { csArr ->
            for (i in 0 until csArr.length()) {
                val csObj = csArr.optJSONObject(i) ?: continue
                val matches = jsonStringArrayToList(csObj.optJSONArray("matches")).ifEmpty { listOf("<all_urls>") }
                val excludeMatches = jsonStringArrayToList(csObj.optJSONArray("exclude_matches"))
                val jsFiles = jsonStringArrayToList(csObj.optJSONArray("js")).map { it.removePrefix("/") }
                val cssFiles = jsonStringArrayToList(csObj.optJSONArray("css")).map { it.removePrefix("/") }
                val runAt = csObj.optString("run_at", "document_end").ifBlank { "document_end" }
                val allFrames = csObj.optBoolean("all_frames", false)

                if (jsFiles.isNotEmpty() || cssFiles.isNotEmpty()) {
                    contentRules.add(
                        ExtensionContentScriptRule(
                            matches = matches,
                            excludeMatches = excludeMatches,
                            jsFiles = jsFiles,
                            cssFiles = cssFiles,
                            runAt = runAt,
                            allFrames = allFrames
                        )
                    )
                }
            }
        }

        // If an extension has no explicit content_scripts entry, check if it has a background/inject script or root content.js
        if (contentRules.isEmpty()) {
            val fallbackJs = listOf("content.js", "inject.js", "script.js", "main.js")
                .filter { File(baseDir, it).exists() }
            val fallbackCss = listOf("style.css", "content.css", "styles.css")
                .filter { File(baseDir, it).exists() }
            if (fallbackJs.isNotEmpty() || fallbackCss.isNotEmpty()) {
                contentRules.add(
                    ExtensionContentScriptRule(
                        matches = hostPermissions.ifEmpty { listOf("<all_urls>") },
                        jsFiles = fallbackJs,
                        cssFiles = fallbackCss,
                        runAt = "document_end"
                    )
                )
            }
        }

        // Parse declarative_net_request rules if present
        val netRules = mutableListOf<ExtensionNetRule>()
        manifest.optJSONObject("declarative_net_request")
            ?.optJSONArray("rule_resources")
            ?.let { resArr ->
                for (i in 0 until resArr.length()) {
                    val resObj = resArr.optJSONObject(i) ?: continue
                    val enabled = resObj.optBoolean("enabled", true)
                    val path = resObj.optString("path", "").trim().removePrefix("/")
                    if (enabled && path.isNotBlank()) {
                        val ruleFile = File(baseDir, path)
                        if (ruleFile.exists()) {
                            netRules.addAll(parseDeclarativeNetRulesFile(ruleFile))
                        }
                    }
                }
            }

        return BrowserExtension(
            id = extId,
            name = resolvedName,
            version = version,
            description = resolvedDesc,
            kind = kind,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = sourceFileName,
            installDirPath = baseDir.absolutePath,
            popupPath = popupPath,
            backgroundScriptPath = backgroundScriptPath,
            permissions = permissions,
            hostPermissions = hostPermissions,
            contentScripts = contentRules,
            netRules = netRules
        )
    }

    private fun installDirectManifestJsonExtension(fileName: String, manifestJsonText: String): BrowserExtension {
        val extId = "ext_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val extDir = File(extensionsRootDir, extId).apply { mkdirs() }
        File(extDir, "manifest.json").writeText(manifestJsonText, Charsets.UTF_8)
        return parseWebExtensionManifest(extId, fileName, extDir, manifestJsonText)
    }

    private fun installUserScriptExtension(fileName: String, scriptContent: String): BrowserExtension {
        val extId = "userscript_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val extDir = File(extensionsRootDir, extId).apply { mkdirs() }
        val scriptFile = File(extDir, "userscript.user.js")
        scriptFile.writeText(scriptContent, Charsets.UTF_8)

        var name = fileName.removeSuffix(".user.js").removeSuffix(".js")
            .replace('_', ' ').replace('-', ' ').trim().ifBlank { "Imported Userscript" }
        var version = "1.0.0"
        var description = "Userscript imported from local storage"
        val matches = mutableListOf<String>()
        val excludes = mutableListOf<String>()
        var runAt = "document_end"

        val metaBlockRegex = Regex("""//\s*==UserScript==([\s\S]*?)//\s*==/UserScript==""", RegexOption.IGNORE_CASE)
        val metaMatch = metaBlockRegex.find(scriptContent)
        if (metaMatch != null) {
            val lines = metaMatch.groupValues[1].lines()
            for (line in lines) {
                val m = Regex("""^//\s*@(\S+)\s+(.+)$""").find(line.trim()) ?: continue
                val key = m.groupValues[1].lowercase(Locale.US)
                val value = m.groupValues[2].trim()
                when (key) {
                    "name" -> if (value.isNotBlank()) name = value
                    "version" -> if (value.isNotBlank()) version = value
                    "description" -> if (value.isNotBlank()) description = value
                    "match", "include" -> if (value.isNotBlank()) matches.add(value)
                    "exclude" -> if (value.isNotBlank()) excludes.add(value)
                    "run-at", "run_at" -> {
                        runAt = when (value.lowercase(Locale.US)) {
                            "document-start", "document_start" -> "document_start"
                            "document-idle", "document_idle" -> "document_idle"
                            else -> "document_end"
                        }
                    }
                }
            }
        }
        if (matches.isEmpty()) {
            matches.add("<all_urls>")
        }

        return BrowserExtension(
            id = extId,
            name = name,
            version = version,
            description = description,
            kind = ExtensionKind.USERSCRIPT,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = fileName,
            installDirPath = extDir.absolutePath,
            permissions = listOf("storage", "GM_getValue", "GM_setValue", "GM_xmlhttpRequest", "GM_addStyle"),
            hostPermissions = matches,
            contentScripts = listOf(
                ExtensionContentScriptRule(
                    matches = matches,
                    excludeMatches = excludes,
                    jsFiles = listOf(scriptFile.name),
                    inlineJs = scriptContent,
                    runAt = runAt,
                    allFrames = true
                )
            )
        )
    }

    private fun installUserStyleExtension(fileName: String, cssContent: String): BrowserExtension {
        val extId = "userstyle_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val extDir = File(extensionsRootDir, extId).apply { mkdirs() }
        val cssFile = File(extDir, "style.user.css")
        cssFile.writeText(cssContent, Charsets.UTF_8)

        var name = fileName.removeSuffix(".user.css").removeSuffix(".css")
            .replace('_', ' ').replace('-', ' ').trim().ifBlank { "Imported UserStyle" }
        var version = "1.0.0"
        var description = "Custom CSS UserStyle imported from local storage"

        val metaBlockRegex = Regex("""/\*\s*==UserStyle==([\s\S]*?)==/UserStyle==\s*\*/""", RegexOption.IGNORE_CASE)
        val metaMatch = metaBlockRegex.find(cssContent)
        if (metaMatch != null) {
            for (line in metaMatch.groupValues[1].lines()) {
                val m = Regex("""^@(\S+)\s+(.+)$""").find(line.trim()) ?: continue
                val key = m.groupValues[1].lowercase(Locale.US)
                val value = m.groupValues[2].trim()
                when (key) {
                    "name" -> if (value.isNotBlank()) name = value
                    "version" -> if (value.isNotBlank()) version = value
                    "description" -> if (value.isNotBlank()) description = value
                }
            }
        }

        return BrowserExtension(
            id = extId,
            name = name,
            version = version,
            description = description,
            kind = ExtensionKind.USERSTYLE,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = fileName,
            installDirPath = extDir.absolutePath,
            permissions = listOf("styling"),
            hostPermissions = listOf("<all_urls>"),
            contentScripts = listOf(
                ExtensionContentScriptRule(
                    matches = listOf("<all_urls>"),
                    cssFiles = listOf(cssFile.name),
                    inlineCss = cssContent,
                    runAt = "document_start",
                    allFrames = true
                )
            )
        )
    }

    // Evaluates whether a given URL matches an extension's match patterns (<all_urls>, domain wildcards, etc.).
    fun matchesUrlPattern(
        url: String,
        patterns: List<String>,
        excludePatterns: List<String> = emptyList()
    ): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return false
        }
        for (ex in excludePatterns) {
            if (singlePatternMatches(url, ex)) return false
        }
        if (patterns.isEmpty()) return true
        return patterns.any { singlePatternMatches(url, it) }
    }

    private fun singlePatternMatches(url: String, rawPattern: String): Boolean {
        val pattern = rawPattern.trim()
        if (pattern.isBlank() || pattern == "<all_urls>" || pattern == "*://*/*" || pattern == "*") {
            return true
        }
        val parsedUri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = parsedUri.scheme?.lowercase(Locale.US) ?: return false
        val host = parsedUri.host?.lowercase(Locale.US) ?: return false
        val fullPath = (parsedUri.encodedPath?.ifBlank { "/" } ?: "/") +
            (parsedUri.encodedQuery?.let { "?$it" } ?: "")

        // Standard WebExtension pattern: <scheme>://<host><path>
        val matchPatternRegex = Regex("""^(\*|http|https)://([^/]+)(/.*)?$""", RegexOption.IGNORE_CASE)
        val m = matchPatternRegex.find(pattern)
        if (m != null) {
            val patScheme = m.groupValues[1].lowercase(Locale.US)
            val patHost = m.groupValues[2].lowercase(Locale.US)
            val patPath = m.groupValues[3].ifBlank { "/*" }

            if (patScheme != "*" && patScheme != "http*" && patScheme != scheme) {
                return false
            }
            val hostMatched = when {
                patHost == "*" -> true
                patHost.startsWith("*.") -> {
                    val baseDomain = patHost.removePrefix("*.")
                    host == baseDomain || host.endsWith(".$baseDomain")
                }
                patHost.contains('*') -> globToRegex(patHost).matches(host)
                else -> host == patHost
            }
            if (!hostMatched) return false

            return globToRegex(patPath).matches(fullPath)
        }

        // Fallback glob match against full URL
        return globToRegex(pattern).matches(url)
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> sb.append("\\").append(ch)
                else -> sb.append(ch)
            }
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /**
     * Builds the JavaScript payload to inject into a WebView at `document_start` or `document_end`
     * for all currently enabled extensions whose match patterns match [url].
     */
    fun buildInjectionPayloadForUrl(url: String, phase: String): String {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return ""
        }
        val activeExtensions = _extensionsFlow.value.filter { it.enabled }
        if (activeExtensions.isEmpty()) return ""

        val isStartPhase = phase == "document_start"
        val sb = StringBuilder()

        // 1. Always ensure the global Dragon Browser WebExtension & Userscript API Polyfill is initialized
        sb.append(buildGlobalExtensionApiPolyfillJs())

        // 2. For each enabled extension, check matching contentScript rules for this phase
        var hasMatchingPayload = false
        for (ext in activeExtensions) {
            // If extension has a background script and we are in document_end, also run its non-DOM helper hooks safely
            for ((ruleIndex, rule) in ext.contentScripts.withIndex()) {
                val ruleMatchesPhase = if (isStartPhase) {
                    rule.runAt.equals("document_start", ignoreCase = true)
                } else {
                    !rule.runAt.equals("document_start", ignoreCase = true)
                }
                // Note: CSS from document_start is also verified at document_end in case `<head>` was recreated
                val shouldInjectCssHere = ruleMatchesPhase || (!isStartPhase && rule.runAt.equals("document_start", ignoreCase = true))
                val shouldInjectJsHere = ruleMatchesPhase

                if (!shouldInjectCssHere && !shouldInjectJsHere) continue
                if (!matchesUrlPattern(url, rule.matches, rule.excludeMatches)) continue

                val cssChunks = mutableListOf<String>()
                if (shouldInjectCssHere) {
                    if (rule.inlineCss.isNotBlank()) {
                        cssChunks.add(rule.inlineCss)
                    } else if (ext.installDirPath.isNotBlank()) {
                        val baseDir = File(ext.installDirPath)
                        for (cssRel in rule.cssFiles) {
                            val f = File(baseDir, cssRel)
                            if (f.exists() && f.isFile) {
                                runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.let {
                                    if (it.isNotBlank()) cssChunks.add(it)
                                }
                            }
                        }
                    }
                }

                val jsChunks = mutableListOf<String>()
                if (shouldInjectJsHere) {
                    if (rule.inlineJs.isNotBlank()) {
                        jsChunks.add(rule.inlineJs)
                    } else if (ext.installDirPath.isNotBlank()) {
                        val baseDir = File(ext.installDirPath)
                        for (jsRel in rule.jsFiles) {
                            val f = File(baseDir, jsRel)
                            if (f.exists() && f.isFile) {
                                runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.let {
                                    if (it.isNotBlank()) jsChunks.add(it)
                                }
                            }
                        }
                    }
                }

                if (cssChunks.isEmpty() && jsChunks.isEmpty()) continue
                hasMatchingPayload = true

                val styleId = "dragon-ext-css-${ext.id}-$ruleIndex"
                if (cssChunks.isNotEmpty()) {
                    val combinedCss = cssChunks.joinToString("\n")
                    val escapedCss = JSONObject.quote(combinedCss)
                    sb.append(
                        """
                        (function() {
                            try {
                                var styleId = "$styleId";
                                if (!document.getElementById(styleId)) {
                                    var st = document.createElement('style');
                                    st.id = styleId;
                                    st.setAttribute('data-dragon-extension', ${JSONObject.quote(ext.name)});
                                    st.textContent = $escapedCss;
                                    (document.head || document.documentElement).appendChild(st);
                                }
                            } catch (e) {}
                        })();
                        """.trimIndent()
                    ).append("\n")
                }

                if (jsChunks.isNotEmpty()) {
                    val guardKey = "__dragon_ext_ran_${ext.id}_${ruleIndex}_${phase}"
                    val initialKvJson = getExtensionStorageJson(ext.id)
                    val extMetaJson = JSONObject().apply {
                        put("id", ext.id)
                        put("name", ext.name)
                        put("version", ext.version)
                        put("description", ext.description)
                    }.toString()

                    for ((jsIdx, jsCode) in jsChunks.withIndex()) {
                        sb.append(
                            """
                            (function() {
                                try {
                                    var guard = "${guardKey}_${jsIdx}_" + location.href;
                                    if (window[guard]) return;
                                    window[guard] = true;
                                    var __extScope = window.__dragonCreateExtensionScope(${JSONObject.quote(ext.id)}, $extMetaJson, $initialKvJson);
                                    var chrome = __extScope.chrome;
                                    var browser = __extScope.browser;
                                    var GM_info = __extScope.GM_info;
                                    var GM_getValue = __extScope.GM_getValue;
                                    var GM_setValue = __extScope.GM_setValue;
                                    var GM_deleteValue = __extScope.GM_deleteValue;
                                    var GM_listValues = __extScope.GM_listValues;
                                    var GM_addStyle = __extScope.GM_addStyle;
                                    var GM_xmlhttpRequest = __extScope.GM_xmlhttpRequest;
                                    var GM_openInTab = __extScope.GM_openInTab;
                                    var GM_setClipboard = __extScope.GM_setClipboard;
                                    var GM_notification = __extScope.GM_notification;
                                    var GM_registerMenuCommand = __extScope.GM_registerMenuCommand;
                                    var GM = __extScope.GM;
                                    var unsafeWindow = window;
                                    $jsCode
                                } catch (err) {
                                    console.warn("[DragonExt:${ext.name}] content script error:", err);
                                }
                            })();
                            """.trimIndent()
                        ).append("\n")
                    }
                }
            }
        }

        return if (hasMatchingPayload) sb.toString() else ""
    }

    /**
     * Intercepts `chrome-extension://<extId>/<path>`, `https://dragon-ext.local/<extId>/<path>`,
     * and active extensions' `declarative_net_request` rules inside `WebViewClient.shouldInterceptRequest`.
     */
    fun interceptExtensionRequest(
        requestUrl: String,
        pageHost: String?,
        isMainFrame: Boolean
    ): WebResourceResponse? {
        val lower = requestUrl.lowercase(Locale.US)

        // 1. Serve extension bundled assets (`chrome-extension://`, `moz-extension://`, `https://dragon-ext.local/`)
        if (lower.startsWith("chrome-extension://") ||
            lower.startsWith("moz-extension://") ||
            lower.startsWith("https://dragon-ext.local/")
        ) {
            val parsed = runCatching { Uri.parse(requestUrl) }.getOrNull() ?: return null
            val extId: String
            val relPath: String
            if (lower.startsWith("https://dragon-ext.local/")) {
                val segs = parsed.pathSegments
                if (segs.size < 2) return null
                extId = segs[0]
                relPath = segs.drop(1).joinToString("/")
            } else {
                extId = parsed.host.orEmpty()
                relPath = parsed.path?.removePrefix("/").orEmpty()
            }
            val ext = _extensionsFlow.value.find { it.id.equals(extId, ignoreCase = true) } ?: return null
            if (ext.installDirPath.isBlank() || relPath.isBlank()) return null

            val targetFile = File(ext.installDirPath, relPath)
            val canonicalBase = runCatching { File(ext.installDirPath).canonicalPath }.getOrNull() ?: return null
            val canonicalTarget = runCatching { targetFile.canonicalPath }.getOrNull() ?: return null
            if (!canonicalTarget.startsWith(canonicalBase) || !targetFile.exists() || !targetFile.isFile) {
                return null
            }

            val mime = guessMimeTypeForFile(targetFile.name)
            return WebResourceResponse(
                mime,
                "UTF-8",
                200,
                "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                    "Cache-Control" to "no-cache"
                ),
                FileInputStream(targetFile)
            )
        }

        // 2. Check active extensions' declarative_net_request rules for HTTP/HTTPS traffic
        if (!isMainFrame && (lower.startsWith("http://") || lower.startsWith("https://"))) {
            val activeExts = _extensionsFlow.value.filter { it.enabled && it.netRules.isNotEmpty() }
            for (ext in activeExts) {
                for (rule in ext.netRules) {
                    if (matchesDeclarativeNetRule(requestUrl, pageHost, rule)) {
                        if (rule.actionType.equals("block", ignoreCase = true)) {
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                200,
                                "OK",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                }
            }
        }

        return null
    }

    private fun matchesDeclarativeNetRule(
        requestUrl: String,
        pageHost: String?,
        rule: ExtensionNetRule
    ): Boolean {
        val host = pageHost?.lowercase(Locale.US).orEmpty()
        if (rule.excludedInitiatorDomains.any { d -> host == d || host.endsWith(".$d") }) {
            return false
        }
        if (rule.initiatorDomains.isNotEmpty() &&
            rule.initiatorDomains.none { d -> host == d || host.endsWith(".$d") }
        ) {
            return false
        }
        val filter = rule.urlFilter.trim()
        if (filter.isBlank()) return false

        // Handle Adblock/DNR style `||domain.com^` or substring/wildcard filters
        val cleaned = filter
            .removePrefix("||")
            .removeSuffix("^")
            .lowercase(Locale.US)
        return if (cleaned.contains('*')) {
            globToRegex("*$cleaned*").matches(requestUrl)
        } else {
            requestUrl.lowercase(Locale.US).contains(cleaned)
        }
    }

    /**
     * Reads the extension's `popup.html` and injects the extension API bridge so its popup UI works interactively.
     */
    fun loadExtensionPopupHtml(ext: BrowserExtension): Pair<String, String>? {
        val popupRel = ext.popupPath ?: return null
        if (ext.installDirPath.isBlank()) return null
        val popupFile = File(ext.installDirPath, popupRel)
        if (!popupFile.exists() || !popupFile.isFile) return null

        val rawHtml = runCatching { popupFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val initialKvJson = getExtensionStorageJson(ext.id)
        val extMetaJson = JSONObject().apply {
            put("id", ext.id)
            put("name", ext.name)
            put("version", ext.version)
            put("description", ext.description)
        }.toString()

        val bootstrapScript = """
            <script>
            ${buildGlobalExtensionApiPolyfillJs()}
            (function() {
                var scope = window.__dragonCreateExtensionScope(${JSONObject.quote(ext.id)}, $extMetaJson, $initialKvJson);
                window.chrome = scope.chrome;
                window.browser = scope.browser;
            })();
            </script>
        """.trimIndent()

        val baseUrl = "https://dragon-ext.local/${ext.id}/" +
            (popupRel.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" })

        val enhancedHtml = if (rawHtml.contains("<head>", ignoreCase = true)) {
            rawHtml.replaceFirst(Regex("<head>", RegexOption.IGNORE_CASE), "<head>\n$bootstrapScript\n")
        } else {
            "$bootstrapScript\n$rawHtml"
        }
        return baseUrl to enhancedHtml
    }

    // --- Native Bridge Storage & Cross-Origin XHR helpers for `@JavascriptInterface` ---

    fun getExtensionStorageJson(extId: String): String {
        return kvStoragePrefs.getString("kv_$extId", "{}") ?: "{}"
    }

    fun mergeExtensionStorageJson(extId: String, patchJson: String): String {
        synchronized(kvStoragePrefs) {
            val currentObj = runCatching { JSONObject(getExtensionStorageJson(extId)) }.getOrElse { JSONObject() }
            val patchObj = runCatching { JSONObject(patchJson) }.getOrElse { JSONObject() }
            val keys = patchObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                currentObj.put(k, patchObj.get(k))
            }
            val saved = currentObj.toString()
            kvStoragePrefs.edit().putString("kv_$extId", saved).apply()
            return saved
        }
    }

    fun removeExtensionStorageKeys(extId: String, keysJsonArray: String): String {
        synchronized(kvStoragePrefs) {
            val currentObj = runCatching { JSONObject(getExtensionStorageJson(extId)) }.getOrElse { JSONObject() }
            val arr = runCatching { JSONArray(keysJsonArray) }.getOrElse { JSONArray() }
            for (i in 0 until arr.length()) {
                currentObj.remove(arr.optString(i))
            }
            val saved = currentObj.toString()
            kvStoragePrefs.edit().putString("kv_$extId", saved).apply()
            return saved
        }
    }

    fun clearExtensionStorage(extId: String) {
        synchronized(kvStoragePrefs) {
            kvStoragePrefs.edit().putString("kv_$extId", "{}").apply()
        }
    }

    /**
     * Executes a cross-origin HTTP request on behalf of an extension's `GM_xmlhttpRequest` or background fetch.
     */
    fun executeCrossOriginXhr(requestJson: String): String {
        return try {
            val reqObj = JSONObject(requestJson)
            val method = reqObj.optString("method", "GET").uppercase(Locale.US)
            val url = reqObj.optString("url", "").trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return JSONObject().apply {
                    put("status", 0)
                    put("statusText", "Invalid URL")
                    put("responseText", "")
                }.toString()
            }
            val headersObj = reqObj.optJSONObject("headers")
            val bodyData = if (reqObj.has("data") && !reqObj.isNull("data")) reqObj.optString("data") else null

            val builder = Request.Builder().url(url)
            var contentType = "text/plain; charset=utf-8"
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val hName = keys.next()
                    val hVal = headersObj.optString(hName, "")
                    if (hName.equals("Content-Type", ignoreCase = true)) {
                        contentType = hVal
                    }
                    builder.header(hName, hVal)
                }
            }

            if (method == "POST" || method == "PUT" || method == "PATCH") {
                val body = (bodyData ?: "").toRequestBody(contentType.toMediaTypeOrNull())
                builder.method(method, body)
            } else {
                builder.method(method, null)
            }

            crossOriginHttpClient.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val respHeaders = StringBuilder()
                for (i in 0 until resp.headers.size) {
                    respHeaders.append(resp.headers.name(i)).append(": ").append(resp.headers.value(i)).append("\r\n")
                }
                JSONObject().apply {
                    put("status", resp.code)
                    put("statusText", resp.message)
                    put("responseText", text)
                    put("responseHeaders", respHeaders.toString())
                    put("finalUrl", resp.request.url.toString())
                }.toString()
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", 0)
                put("statusText", e.message ?: "Network Error")
                put("responseText", "")
            }.toString()
        }
    }

    private fun buildGlobalExtensionApiPolyfillJs(): String {
        return """
        (function() {
            if (window.__dragonCreateExtensionScope) return;
            window.__dragonExtMessageListeners = window.__dragonExtMessageListeners || [];
            window.__dragonExtStorageCache = window.__dragonExtStorageCache || {};

            window.__dragonCreateExtensionScope = function(extId, extMeta, initialKv) {
                window.__dragonExtStorageCache[extId] = Object.assign({}, initialKv || {}, window.__dragonExtStorageCache[extId] || {});

                var readStore = function() {
                    try {
                        if (window.DragonExtensionBridge && window.DragonExtensionBridge.storageGet) {
                            var raw = window.DragonExtensionBridge.storageGet(extId);
                            if (raw) {
                                window.__dragonExtStorageCache[extId] = JSON.parse(raw);
                            }
                        }
                    } catch (e) {}
                    return window.__dragonExtStorageCache[extId] || {};
                };

                var storageArea = {
                    get: function(keys, callback) {
                        var store = readStore();
                        var result = {};
                        if (!keys) {
                            result = Object.assign({}, store);
                        } else if (typeof keys === 'string') {
                            if (keys in store) result[keys] = store[keys];
                        } else if (Array.isArray(keys)) {
                            for (var i = 0; i < keys.length; i++) {
                                var k = keys[i];
                                if (k in store) result[k] = store[k];
                            }
                        } else if (typeof keys === 'object') {
                            for (var prop in keys) {
                                result[prop] = (prop in store) ? store[prop] : keys[prop];
                            }
                        }
                        if (typeof callback === 'function') {
                            setTimeout(function() { callback(result); }, 0);
                        }
                        return Promise.resolve(result);
                    },
                    set: function(items, callback) {
                        try {
                            var patch = items || {};
                            Object.assign(window.__dragonExtStorageCache[extId], patch);
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.storageSet) {
                                window.DragonExtensionBridge.storageSet(extId, JSON.stringify(patch));
                            }
                        } catch (e) {}
                        if (typeof callback === 'function') {
                            setTimeout(function() { callback(); }, 0);
                        }
                        return Promise.resolve();
                    },
                    remove: function(keys, callback) {
                        try {
                            var arr = Array.isArray(keys) ? keys : [String(keys)];
                            for (var i = 0; i < arr.length; i++) {
                                delete window.__dragonExtStorageCache[extId][arr[i]];
                            }
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.storageRemove) {
                                window.DragonExtensionBridge.storageRemove(extId, JSON.stringify(arr));
                            }
                        } catch (e) {}
                        if (typeof callback === 'function') {
                            setTimeout(function() { callback(); }, 0);
                        }
                        return Promise.resolve();
                    },
                    clear: function(callback) {
                        try {
                            window.__dragonExtStorageCache[extId] = {};
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.storageClear) {
                                window.DragonExtensionBridge.storageClear(extId);
                            }
                        } catch (e) {}
                        if (typeof callback === 'function') {
                            setTimeout(function() { callback(); }, 0);
                        }
                        return Promise.resolve();
                    }
                };

                var onMessageEvent = {
                    addListener: function(fn) {
                        if (typeof fn === 'function') window.__dragonExtMessageListeners.push(fn);
                    },
                    removeListener: function(fn) {
                        var idx = window.__dragonExtMessageListeners.indexOf(fn);
                        if (idx >= 0) window.__dragonExtMessageListeners.splice(idx, 1);
                    },
                    hasListener: function(fn) {
                        return window.__dragonExtMessageListeners.indexOf(fn) >= 0;
                    }
                };

                var chromeObj = {
                    runtime: {
                        id: extId,
                        lastError: null,
                        getManifest: function() { return extMeta || { name: extId, version: "1.0" }; },
                        getURL: function(path) {
                            var clean = String(path || '').replace(/^\/+/, '');
                            return 'https://dragon-ext.local/' + extId + '/' + clean;
                        },
                        sendMessage: function(message, responseCallback) {
                            var cb = typeof responseCallback === 'function' ? responseCallback : null;
                            setTimeout(function() {
                                var responded = false;
                                var sendResponse = function(reply) {
                                    if (!responded && cb) {
                                        responded = true;
                                        cb(reply);
                                    }
                                };
                                for (var i = 0; i < window.__dragonExtMessageListeners.length; i++) {
                                    try {
                                        window.__dragonExtMessageListeners[i](message, { id: extId, url: location.href }, sendResponse);
                                    } catch (e) {}
                                }
                                if (!responded && cb) cb({});
                            }, 0);
                            return Promise.resolve({});
                        },
                        onMessage: onMessageEvent,
                        onInstalled: { addListener: function(fn) {} }
                    },
                    storage: {
                        local: storageArea,
                        sync: storageArea,
                        session: storageArea,
                        onChanged: { addListener: function() {}, removeListener: function() {} }
                    },
                    i18n: {
                        getMessage: function(key) { return String(key || ''); },
                        getUILanguage: function() { return navigator.language || 'en-US'; }
                    },
                    tabs: {
                        query: function(queryInfo, callback) {
                            var tabs = [{ id: 1, active: true, currentWindow: true, url: location.href, title: document.title }];
                            if (typeof callback === 'function') setTimeout(function() { callback(tabs); }, 0);
                            return Promise.resolve(tabs);
                        },
                        create: function(createProperties, callback) {
                            var targetUrl = createProperties && createProperties.url ? String(createProperties.url) : '';
                            if (targetUrl && window.DragonExtensionBridge && window.DragonExtensionBridge.openTab) {
                                window.DragonExtensionBridge.openTab(targetUrl);
                            }
                            if (typeof callback === 'function') setTimeout(function() { callback({ id: 2, url: targetUrl }); }, 0);
                            return Promise.resolve({ id: 2, url: targetUrl });
                        },
                        sendMessage: function(tabId, message, callback) {
                            return chromeObj.runtime.sendMessage(message, callback);
                        }
                    },
                    scripting: {
                        executeScript: function(injection, callback) {
                            try {
                                if (injection && typeof injection.func === 'function') {
                                    var res = injection.func.apply(window, injection.args || []);
                                    if (typeof callback === 'function') callback([{ result: res }]);
                                    return Promise.resolve([{ result: res }]);
                                }
                            } catch (e) {}
                            return Promise.resolve([]);
                        }
                    }
                };

                var gmGetValue = function(key, defaultValue) {
                    var store = readStore();
                    return (key in store) ? store[key] : defaultValue;
                };
                var gmSetValue = function(key, value) {
                    var patch = {};
                    patch[key] = value;
                    storageArea.set(patch);
                };
                var gmDeleteValue = function(key) {
                    storageArea.remove([key]);
                };
                var gmListValues = function() {
                    return Object.keys(readStore());
                };
                var gmAddStyle = function(css) {
                    var style = document.createElement('style');
                    style.textContent = String(css || '');
                    (document.head || document.documentElement).appendChild(style);
                    return style;
                };
                var gmXhr = function(details) {
                    if (!details || !details.url) return;
                    setTimeout(function() {
                        try {
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.gmXhr) {
                                var rawResp = window.DragonExtensionBridge.gmXhr(JSON.stringify({
                                    method: details.method || 'GET',
                                    url: details.url,
                                    headers: details.headers || {},
                                    data: details.data || null
                                }));
                                var parsed = JSON.parse(rawResp || '{}');
                                var responseObj = {
                                    status: parsed.status || 200,
                                    statusText: parsed.statusText || 'OK',
                                    responseText: parsed.responseText || '',
                                    response: parsed.responseText || '',
                                    responseHeaders: parsed.responseHeaders || '',
                                    finalUrl: parsed.finalUrl || details.url,
                                    readyState: 4
                                };
                                if (parsed.status > 0 && typeof details.onload === 'function') {
                                    details.onload(responseObj);
                                } else if (parsed.status === 0 && typeof details.onerror === 'function') {
                                    details.onerror(responseObj);
                                }
                            } else {
                                fetch(details.url, {
                                    method: details.method || 'GET',
                                    headers: details.headers || {},
                                    body: details.data || undefined
                                }).then(function(r) {
                                    return r.text().then(function(t) {
                                        if (typeof details.onload === 'function') {
                                            details.onload({ status: r.status, statusText: r.statusText, responseText: t, response: t, finalUrl: r.url, readyState: 4 });
                                        }
                                    });
                                }).catch(function(err) {
                                    if (typeof details.onerror === 'function') details.onerror(err);
                                });
                            }
                        } catch (e) {
                            if (typeof details.onerror === 'function') details.onerror(e);
                        }
                    }, 0);
                };
                var gmOpenInTab = function(url) {
                    if (url && window.DragonExtensionBridge && window.DragonExtensionBridge.openTab) {
                        window.DragonExtensionBridge.openTab(String(url));
                    } else if (url) {
                        window.open(String(url), '_blank');
                    }
                };
                var gmSetClipboard = function(text) {
                    if (window.DragonExtensionBridge && window.DragonExtensionBridge.copyClipboard) {
                        window.DragonExtensionBridge.copyClipboard(String(text || ''));
                    } else if (navigator.clipboard && navigator.clipboard.writeText) {
                        navigator.clipboard.writeText(String(text || ''));
                    }
                };
                var gmNotification = function(textOrOpts, title) {
                    var msg = (typeof textOrOpts === 'object' && textOrOpts !== null) ? (textOrOpts.text || '') : String(textOrOpts || '');
                    if (msg && window.DragonExtensionBridge && window.DragonExtensionBridge.showToast) {
                        window.DragonExtensionBridge.showToast(msg);
                    }
                };

                if (!window.chrome || !window.chrome.storage) {
                    window.chrome = Object.assign(window.chrome || {}, chromeObj);
                }
                if (!window.browser) {
                    window.browser = chromeObj;
                }

                return {
                    chrome: chromeObj,
                    browser: chromeObj,
                    GM_info: { script: extMeta || { name: extId, version: '1.0' }, scriptHandler: 'DragonBrowserExtensions', version: '3.0' },
                    GM_getValue: gmGetValue,
                    GM_setValue: gmSetValue,
                    GM_deleteValue: gmDeleteValue,
                    GM_listValues: gmListValues,
                    GM_addStyle: gmAddStyle,
                    GM_xmlhttpRequest: gmXhr,
                    GM_openInTab: gmOpenInTab,
                    GM_setClipboard: gmSetClipboard,
                    GM_notification: gmNotification,
                    GM_registerMenuCommand: function(caption, fn) {},
                    GM: {
                        info: { script: extMeta || { name: extId, version: '1.0' } },
                        getValue: function(k, d) { return Promise.resolve(gmGetValue(k, d)); },
                        setValue: function(k, v) { gmSetValue(k, v); return Promise.resolve(); },
                        deleteValue: function(k) { gmDeleteValue(k); return Promise.resolve(); },
                        listValues: function() { return Promise.resolve(gmListValues()); },
                        addStyle: function(c) { return Promise.resolve(gmAddStyle(c)); },
                        xmlHttpRequest: gmXhr,
                        openInTab: gmOpenInTab,
                        setClipboard: gmSetClipboard,
                        notification: gmNotification
                    }
                };
            };
        })();
        """.trimIndent() + "\n"
    }

    private fun createBuiltInExtensions(): List<BrowserExtension> {
        return listOf(
            BrowserExtension(
                id = "builtin_unlock_copy",
                name = "Copy & Right-Click Unlocker",
                version = "2.1.0",
                description = "Unlocks text selection, copy/paste, and context menus on websites that block copying.",
                kind = ExtensionKind.WEB_EXTENSION_MV3,
                enabled = true,
                isBuiltIn = true,
                sourceFileName = "built-in",
                permissions = listOf("scripting", "activeTab"),
                hostPermissions = listOf("<all_urls>"),
                contentScripts = listOf(
                    ExtensionContentScriptRule(
                        matches = listOf("<all_urls>"),
                        inlineCss = """
                            * {
                                -webkit-user-select: text !important;
                                user-select: text !important;
                            }
                        """.trimIndent(),
                        inlineJs = """
                            ['copy', 'cut', 'paste', 'selectstart', 'contextmenu', 'dragstart'].forEach(function(evt) {
                                window.addEventListener(evt, function(e) {
                                    e.stopImmediatePropagation();
                                }, true);
                            });
                            if (document.body) {
                                document.body.oncopy = null;
                                document.body.onselectstart = null;
                                document.body.oncontextmenu = null;
                            }
                        """.trimIndent(),
                        runAt = "document_end",
                        allFrames = true
                    )
                )
            ),
            BrowserExtension(
                id = "builtin_bg_audio_keepalive",
                name = "Background Video & Audio Keep-Alive",
                version = "1.8.0",
                description = "Prevents streaming sites from auto-pausing playback when switching tabs or minimizing.",
                kind = ExtensionKind.WEB_EXTENSION_MV3,
                enabled = true,
                isBuiltIn = true,
                sourceFileName = "built-in",
                permissions = listOf("scripting"),
                hostPermissions = listOf("<all_urls>"),
                contentScripts = listOf(
                    ExtensionContentScriptRule(
                        matches = listOf("<all_urls>"),
                        inlineJs = """
                            Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                            Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                            window.addEventListener('visibilitychange', function(e) {
                                e.stopImmediatePropagation();
                            }, true);
                        """.trimIndent(),
                        runAt = "document_start",
                        allFrames = true
                    )
                )
            ),
            BrowserExtension(
                id = "builtin_video_speed_hud",
                name = "Video Speed & PiP Controller",
                version = "3.0.0",
                description = "Adds a compact floating 1x/1.5x/2x/PiP speed pill on HTML5 video players when tapped.",
                kind = ExtensionKind.USERSCRIPT,
                enabled = false,
                isBuiltIn = true,
                sourceFileName = "built-in",
                permissions = listOf("activeTab", "storage"),
                hostPermissions = listOf("<all_urls>"),
                contentScripts = listOf(
                    ExtensionContentScriptRule(
                        matches = listOf("<all_urls>"),
                        inlineJs = """
                            (function() {
                                function attachSpeedBadge() {
                                    var videos = document.querySelectorAll('video');
                                    if (!videos.length || document.getElementById('dragon-ext-vspeed-hud')) return;
                                    var hud = document.createElement('div');
                                    hud.id = 'dragon-ext-vspeed-hud';
                                    hud.style.cssText = 'position:fixed;top:64px;right:10px;z-index:2147483646;background:rgba(8,20,30,0.86);color:#00E5FF;border:1px solid rgba(0,229,255,0.45);border-radius:16px;padding:4px 9px;font:700 11px monospace;display:flex;gap:7px;align-items:center;box-shadow:0 2px 10px rgba(0,0,0,0.45);user-select:none;';
                                    var speeds = [1.0, 1.25, 1.5, 2.0];
                                    var idx = 0;
                                    var speedBtn = document.createElement('span');
                                    speedBtn.textContent = '1.0x';
                                    speedBtn.style.cursor = 'pointer';
                                    speedBtn.onclick = function(e) {
                                        e.stopPropagation();
                                        idx = (idx + 1) % speeds.length;
                                        var next = speeds[idx];
                                        document.querySelectorAll('video').forEach(function(v) { v.playbackRate = next; });
                                        speedBtn.textContent = next + 'x';
                                    };
                                    hud.appendChild(speedBtn);
                                    document.body.appendChild(hud);
                                }
                                setInterval(attachSpeedBadge, 1800);
                                attachSpeedBadge();
                            })();
                        """.trimIndent(),
                        runAt = "document_end",
                        allFrames = false
                    )
                )
            ),
            BrowserExtension(
                id = "builtin_dark_reader",
                name = "Dark Reader Pro (Smart Night Mode)",
                version = "4.2.0",
                description = "Applies a smart high-contrast dark theme to bright websites while preserving videos and images.",
                kind = ExtensionKind.USERSTYLE,
                enabled = false,
                isBuiltIn = true,
                sourceFileName = "built-in",
                permissions = listOf("styling"),
                hostPermissions = listOf("<all_urls>"),
                contentScripts = listOf(
                    ExtensionContentScriptRule(
                        matches = listOf("<all_urls>"),
                        inlineCss = """
                            html {
                                filter: invert(0.92) hue-rotate(180deg) !important;
                                background-color: #111 !important;
                            }
                            img, video, iframe, canvas, svg, picture, [style*="background-image"] {
                                filter: invert(1) hue-rotate(180deg) !important;
                            }
                        """.trimIndent(),
                        runAt = "document_start",
                        allFrames = false
                    )
                )
            )
        )
    }

    private fun loadExtensions() {
        val builtIns = createBuiltInExtensions()
        try {
            val raw = prefs.getString("installed_extensions_v1", null)
            if (raw.isNullOrBlank()) {
                _extensionsFlow.value = builtIns
                persistExtensions(builtIns)
                return
            }
            val arr = JSONArray(raw)
            val loaded = mutableListOf<BrowserExtension>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                val builtInMatch = builtIns.find { it.id == id }
                if (builtInMatch != null) {
                    loaded.add(builtInMatch.copy(enabled = obj.optBoolean("enabled", builtInMatch.enabled)))
                    continue
                }

                val kindName = obj.optString("kind", ExtensionKind.WEB_EXTENSION_MV3.name)
                val kind = runCatching { ExtensionKind.valueOf(kindName) }.getOrDefault(ExtensionKind.WEB_EXTENSION_MV3)

                val rulesArr = obj.optJSONArray("contentScripts") ?: JSONArray()
                val rules = mutableListOf<ExtensionContentScriptRule>()
                for (r in 0 until rulesArr.length()) {
                    val rObj = rulesArr.optJSONObject(r) ?: continue
                    rules.add(
                        ExtensionContentScriptRule(
                            matches = jsonStringArrayToList(rObj.optJSONArray("matches")).ifEmpty { listOf("<all_urls>") },
                            excludeMatches = jsonStringArrayToList(rObj.optJSONArray("excludeMatches")),
                            jsFiles = jsonStringArrayToList(rObj.optJSONArray("jsFiles")),
                            cssFiles = jsonStringArrayToList(rObj.optJSONArray("cssFiles")),
                            inlineJs = rObj.optString("inlineJs", ""),
                            inlineCss = rObj.optString("inlineCss", ""),
                            runAt = rObj.optString("runAt", "document_end"),
                            allFrames = rObj.optBoolean("allFrames", false)
                        )
                    )
                }

                val netRulesArr = obj.optJSONArray("netRules") ?: JSONArray()
                val netRules = mutableListOf<ExtensionNetRule>()
                for (n in 0 until netRulesArr.length()) {
                    val nObj = netRulesArr.optJSONObject(n) ?: continue
                    netRules.add(
                        ExtensionNetRule(
                            id = nObj.optInt("id", n + 1),
                            actionType = nObj.optString("actionType", "block"),
                            urlFilter = nObj.optString("urlFilter", ""),
                            redirectUrl = nObj.optString("redirectUrl", "").takeIf { it.isNotBlank() },
                            initiatorDomains = jsonStringArrayToList(nObj.optJSONArray("initiatorDomains")),
                            excludedInitiatorDomains = jsonStringArrayToList(nObj.optJSONArray("excludedInitiatorDomains"))
                        )
                    )
                }

                loaded.add(
                    BrowserExtension(
                        id = id,
                        name = obj.optString("name", "Extension"),
                        version = obj.optString("version", "1.0.0"),
                        description = obj.optString("description", ""),
                        kind = kind,
                        enabled = obj.optBoolean("enabled", true),
                        isBuiltIn = obj.optBoolean("isBuiltIn", false),
                        sourceFileName = obj.optString("sourceFileName", ""),
                        installDirPath = obj.optString("installDirPath", ""),
                        popupPath = obj.optString("popupPath", "").takeIf { it.isNotBlank() },
                        backgroundScriptPath = obj.optString("backgroundScriptPath", "").takeIf { it.isNotBlank() },
                        permissions = jsonStringArrayToList(obj.optJSONArray("permissions")),
                        hostPermissions = jsonStringArrayToList(obj.optJSONArray("hostPermissions")).ifEmpty { listOf("<all_urls>") },
                        contentScripts = rules,
                        netRules = netRules,
                        installedAtMs = obj.optLong("installedAtMs", System.currentTimeMillis())
                    )
                )
            }
            // Ensure all built-in extensions exist
            for (b in builtIns) {
                if (loaded.none { it.id == b.id }) {
                    loaded.add(b)
                }
            }
            _extensionsFlow.value = loaded
        } catch (_: Exception) {
            _extensionsFlow.value = builtIns
        }
    }

    private fun persistExtensions(list: List<BrowserExtension>) {
        try {
            val arr = JSONArray()
            for (ext in list) {
                val rulesArr = JSONArray()
                for (r in ext.contentScripts) {
                    rulesArr.put(JSONObject().apply {
                        put("matches", JSONArray(r.matches))
                        put("excludeMatches", JSONArray(r.excludeMatches))
                        put("jsFiles", JSONArray(r.jsFiles))
                        put("cssFiles", JSONArray(r.cssFiles))
                        put("inlineJs", r.inlineJs)
                        put("inlineCss", r.inlineCss)
                        put("runAt", r.runAt)
                        put("allFrames", r.allFrames)
                    })
                }
                val netRulesArr = JSONArray()
                for (n in ext.netRules) {
                    netRulesArr.put(JSONObject().apply {
                        put("id", n.id)
                        put("actionType", n.actionType)
                        put("urlFilter", n.urlFilter)
                        put("redirectUrl", n.redirectUrl ?: "")
                        put("initiatorDomains", JSONArray(n.initiatorDomains))
                        put("excludedInitiatorDomains", JSONArray(n.excludedInitiatorDomains))
                    })
                }
                arr.put(JSONObject().apply {
                    put("id", ext.id)
                    put("name", ext.name)
                    put("version", ext.version)
                    put("description", ext.description)
                    put("kind", ext.kind.name)
                    put("enabled", ext.enabled)
                    put("isBuiltIn", ext.isBuiltIn)
                    put("sourceFileName", ext.sourceFileName)
                    put("installDirPath", ext.installDirPath)
                    put("popupPath", ext.popupPath ?: "")
                    put("backgroundScriptPath", ext.backgroundScriptPath ?: "")
                    put("permissions", JSONArray(ext.permissions))
                    put("hostPermissions", JSONArray(ext.hostPermissions))
                    put("contentScripts", rulesArr)
                    put("netRules", netRulesArr)
                    put("installedAtMs", ext.installedAtMs)
                })
            }
            prefs.edit().putString("installed_extensions_v1", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isCrxMagic(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 'C'.code.toByte() &&
            bytes[1] == 'r'.code.toByte() &&
            bytes[2] == '2'.code.toByte() &&
            bytes[3] == '4'.code.toByte()
    }

    /**
     * Finds the byte offset of the ZIP local file header (`PK\x03\x04` = `0x50, 0x4B, 0x03, 0x04`).
     * Works for both standard `.zip`/`.xpi` files (offset 0) and Chrome `.crx` packages (CRX2/CRX3 header before ZIP).
     */
    private fun findZipHeaderOffset(bytes: ByteArray): Int {
        val maxSearch = bytes.size.coerceAtMost(65536) - 4
        for (i in 0..maxSearch) {
            if (bytes[i] == 0x50.toByte() &&
                bytes[i + 1] == 0x4B.toByte() &&
                bytes[i + 2] == 0x03.toByte() &&
                bytes[i + 3] == 0x04.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun findManifestFile(rootDir: File): File? {
        val direct = File(rootDir, "manifest.json")
        if (direct.exists() && direct.isFile) return direct
        return rootDir.walkTopDown().maxDepth(3).firstOrNull {
            it.isFile && it.name.equals("manifest.json", ignoreCase = true)
        }
    }

    private fun loadLocaleMessages(baseDir: File, defaultLocale: String): Map<String, String> {
        val candidates = listOf(
            File(baseDir, "_locales/$defaultLocale/messages.json"),
            File(baseDir, "_locales/en/messages.json"),
            File(baseDir, "_locales/en_US/messages.json")
        )
        val msgFile = candidates.firstOrNull { it.exists() && it.isFile } ?: return emptyMap()
        return try {
            val obj = JSONObject(msgFile.readText(Charsets.UTF_8))
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val msg = obj.optJSONObject(k)?.optString("message", "").orEmpty()
                if (msg.isNotBlank()) {
                    map[k.lowercase(Locale.US)] = msg
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun resolveI18nPlaceholders(raw: String, i18nMap: Map<String, String>): String {
        if (!raw.contains("__MSG_")) return raw
        return Regex("""__MSG_([A-Za-z0-9_@]+)__""").replace(raw) { match ->
            val key = match.groupValues[1].lowercase(Locale.US)
            i18nMap[key] ?: match.value
        }
    }

    private fun parseDeclarativeNetRulesFile(file: File): List<ExtensionNetRule> {
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val list = mutableListOf<ExtensionNetRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optInt("id", i + 1)
                val actionObj = obj.optJSONObject("action") ?: continue
                val actionType = actionObj.optString("type", "block")
                val redirectUrl = actionObj.optJSONObject("redirect")?.optString("url")
                val condObj = obj.optJSONObject("condition") ?: continue
                val urlFilter = condObj.optString("urlFilter", "").ifBlank {
                    condObj.optJSONArray("requestDomains")?.optString(0, "").orEmpty()
                }
                if (urlFilter.isNotBlank()) {
                    list.add(
                        ExtensionNetRule(
                            id = id,
                            actionType = actionType,
                            urlFilter = urlFilter,
                            redirectUrl = redirectUrl,
                            initiatorDomains = jsonStringArrayToList(condObj.optJSONArray("initiatorDomains")).ifEmpty {
                                jsonStringArrayToList(condObj.optJSONArray("domains"))
                            },
                            excludedInitiatorDomains = jsonStringArrayToList(condObj.optJSONArray("excludedInitiatorDomains")).ifEmpty {
                                jsonStringArrayToList(condObj.optJSONArray("excludedDomains"))
                            }
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun jsonStringArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotBlank()) list.add(s)
        }
        return list
    }

    private fun guessMimeTypeForFile(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
    }
}
