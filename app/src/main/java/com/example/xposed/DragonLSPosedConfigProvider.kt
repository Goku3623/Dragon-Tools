package com.example.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.example.data.adblock.AdBlockerEngine
import com.example.data.model.SocialPlatform
import com.example.data.repository.KernelRepository

/**
 * Cross-process ContentProvider that allows LSPosed hooks running inside target apps
 * (Facebook, Instagram, YouTube, TikTok, Snapchat, X, Reddit, Browsers, Games) to read live
 * Ad Blocker & FrameGen configuration on Android 11-16 and report real in-process intercepts.
 */
class DragonLSPosedConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.android.dragontools.lsposed.provider"
        val CONFIG_URI: Uri = Uri.parse("content://$AUTHORITY/config")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "is_adblock_enabled",
                "block_video_ads",
                "block_sponsored_posts",
                "block_telemetry",
                "block_reels_ads",
                "auto_skip_inapp",
                "auto_swipe_reels",
                "framegen_enabled",
                "target_fps",
                "blocked_domains_csv",
                "xposed_hook_litho",
                "xposed_hook_youtube_video",
                "xposed_hook_ad_sdks",
                "xposed_hook_network_webview",
                "xposed_hook_json_graphql",
                "whitelisted_domains_csv",
                "block_quic",
                "disabled_platforms_csv"
            )
        )

        val ctx = context ?: return cursor
        try {
            val (adPrefs, _) = DragonXposedModule.openAdBlockerSharedPrefs(ctx)
            val fgPrefs = ctx.getSharedPreferences("dragon_framegen_prefs", Context.MODE_PRIVATE)

            val globalEnabled = adPrefs.getBoolean("adblock_global_enabled", false)
            val videoAds = adPrefs.getBoolean("adblock_video_ads", true)
            val sponsored = adPrefs.getBoolean("adblock_sponsored", true)
            val telemetry = adPrefs.getBoolean("adblock_telemetry", true)
            val reelsAds = adPrefs.getBoolean("adblock_reels_ads", true)
            val autoSkip = adPrefs.getBoolean("adblock_auto_skip_inapp", true)
            val autoSwipe = adPrefs.getBoolean("adblock_auto_swipe_reels", true)
            val customBlacklist = adPrefs.getStringSet("adblock_custom_blacklist", emptySet()) ?: emptySet()
            val customWhitelist = adPrefs.getStringSet("adblock_custom_whitelist", emptySet()) ?: emptySet()
            val blockQuic = adPrefs.getBoolean("adblock_block_quic", true)

            val disabledPackages = mutableListOf<String>()
            SocialPlatform.values().forEach { platform ->
                if (!adPrefs.getBoolean("adblock_platform_${platform.id}", true)) {
                    disabledPackages.addAll(platform.packageNames)
                }
            }

            val hookLitho = adPrefs.getBoolean("adblock_xposed_litho", true)
            val hookYtVideo = adPrefs.getBoolean("adblock_xposed_yt_video", true)
            val hookAdSdks = adPrefs.getBoolean("adblock_xposed_ad_sdks", true)
            val hookNetWebView = adPrefs.getBoolean("adblock_xposed_net_webview", true)
            val hookJsonGraphQl = adPrefs.getBoolean("adblock_xposed_json_graphql", true)

            val fgEnabled = fgPrefs.getBoolean("fg_enabled", false)
            val targetFps = fgPrefs.getInt("fg_target_fps", 120)

            cursor.addRow(
                arrayOf<Any>(
                    if (globalEnabled) 1 else 0,
                    if (videoAds) 1 else 0,
                    if (sponsored) 1 else 0,
                    if (telemetry) 1 else 0,
                    if (reelsAds) 1 else 0,
                    if (autoSkip) 1 else 0,
                    if (autoSwipe) 1 else 0,
                    if (fgEnabled) 1 else 0,
                    targetFps,
                    customBlacklist.joinToString(","),
                    if (hookLitho) 1 else 0,
                    if (hookYtVideo) 1 else 0,
                    if (hookAdSdks) 1 else 0,
                    if (hookNetWebView) 1 else 0,
                    if (hookJsonGraphQl) 1 else 0,
                    customWhitelist.joinToString(","),
                    if (blockQuic) 1 else 0,
                    disabledPackages.joinToString(",")
                )
            )
        } catch (_: Exception) {
        }
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        when (method) {
            "report_lsposed_intercept" -> {
                try {
                    val pkg = extras?.getString("package_name") ?: arg ?: ""
                    val descriptor = extras?.getString("descriptor") ?: "LSPosed In-Process Hook"
                    val action = extras?.getString("action") ?: "LSPOSED_BLOCKED"
                    val platform = AdBlockerEngine.detectSocialPlatform(pkg) ?: SocialPlatform.OTHER_SOCIAL
                    KernelRepository.getInstance(ctx).recordRealSocialAdIntercept(
                        platform = platform,
                        descriptor = descriptor,
                        action = action
                    )
                } catch (_: Exception) {
                }
            }
            "ping_lsposed_status" -> {
                val detection = DragonXposedModule.detectContainerRuntime(ctx)
                return Bundle().apply {
                    putBoolean("ok", true)
                    putBoolean("in_container", detection.isInContainer)
                    putBoolean("self_hook_active", detection.isSelfHookActive)
                    putString("container_type", detection.containerType.name)
                    putInt("api_version", detection.apiVersion)
                }
            }
        }
        return Bundle().apply { putBoolean("ok", true) }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.config"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
