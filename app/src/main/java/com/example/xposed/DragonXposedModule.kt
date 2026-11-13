package com.example.xposed

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.example.R
import java.io.File
import java.lang.reflect.Modifier

enum class LSPosedContainerType(val label: String) {
    NONE("Standard Android Runtime"),
    LSPOSED_ZYGISK("LSPosed (Zygisk) Container"),
    LSPOSED_RIRU("LSPosed (Riru) Container"),
    LSPOSED_HOOKED("LSPosed Hooked Module Container"),
    LSPATCH_CONTAINER("LSPatch Non-Root Container"),
    XPOSED_LEGACY("Xposed Legacy Container")
}

data class LSPosedRuntimeDetection(
    val isInContainer: Boolean,
    val containerType: LSPosedContainerType,
    val isSelfHookActive: Boolean,
    val isXposedClassLoaderInjected: Boolean,
    val isNativeBridgeDetected: Boolean,
    val isFrameworkFilesPresent: Boolean,
    val apiVersion: Int,
    val isWorldReadablePrefsSupported: Boolean,
    val details: String
)

/**
 * Module interface contract between `com.android.dragontools` application runtime
 * and the Xposed entry point ([DragonLSPosedEntry]).
 */
interface DragonXposedModuleInterface {
    val modulePackageId: String
    val entryPointClassName: String
    val configProviderAuthority: String

    fun isInsideLSPosedContainer(context: Context? = null): Boolean
    fun getContainerType(context: Context? = null): LSPosedContainerType
    fun getActiveXposedApiVersion(): Int
    fun isSelfHookActive(): Boolean
    fun getConfiguredScopePackages(context: Context): List<String>
    fun openAdBlockerSharedPrefs(context: Context): Pair<SharedPreferences, Boolean>
}

object DragonXposedModule : DragonXposedModuleInterface {

    override val modulePackageId: String = "com.android.dragontools"
    override val entryPointClassName: String = "com.example.xposed.DragonLSPosedEntry"
    override val configProviderAuthority: String = "com.android.dragontools.lsposed.provider"

    const val AD_BLOCKER_PREFS_NAME = "dragon_adblocker_prefs"
    private const val MODE_WORLD_READABLE_COMPAT = 1 // Context.MODE_WORLD_READABLE for LSPosed xposedsharedprefs

    /**
     * Hooked directly by [DragonLSPosedEntry] when LSPosed loads `com.android.dragontools`.
     */
    @JvmStatic
    fun isModuleActiveHooked(): Boolean = false

    /**
     * Hooked directly by [DragonLSPosedEntry] to expose live `XposedBridge.getXposedVersion()`.
     */
    @JvmStatic
    fun getHookedApiVersion(): Int = 0

    /**
     * Hooked directly by [DragonLSPosedEntry] to expose active framework tag.
     */
    @JvmStatic
    fun getHookedContainerName(): String = ""

    override fun isSelfHookActive(): Boolean {
        return try {
            isModuleActiveHooked() || DragonLSPosedStatus.isModuleActiveHooked()
        } catch (_: Throwable) {
            false
        }
    }

    override fun getActiveXposedApiVersion(): Int {
        val hookedVersion = try {
            maxOf(getHookedApiVersion(), DragonLSPosedStatus.getHookedApiVersion())
        } catch (_: Throwable) {
            0
        }
        if (hookedVersion > 0) return hookedVersion

        return try {
            val bridgeClass = Class.forName("de.robv.android.xposed.XposedBridge")
            val ver = bridgeClass.getDeclaredMethod("getXposedVersion").invoke(null) as? Int ?: 0
            if (ver > 0) ver else 93
        } catch (_: Throwable) {
            0
        }
    }

    override fun isInsideLSPosedContainer(context: Context?): Boolean {
        return detectContainerRuntime(context).isInContainer
    }

    override fun getContainerType(context: Context?): LSPosedContainerType {
        return detectContainerRuntime(context).containerType
    }

    override fun getConfiguredScopePackages(context: Context): List<String> {
        return try {
            context.resources.getStringArray(R.array.xposed_scope).toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Opens `dragon_adblocker_prefs` using `MODE_WORLD_READABLE` when intercepted by LSPosed's
     * `xposedsharedprefs="true"` service so `XSharedPreferences` in hooked social apps receives
     * live configuration updates even without root SELinux edits. Falls back cleanly to `MODE_PRIVATE`.
     */
    @Suppress("DEPRECATION")
    override fun openAdBlockerSharedPrefs(context: Context): Pair<SharedPreferences, Boolean> {
        return try {
            val worldPrefs = context.getSharedPreferences(AD_BLOCKER_PREFS_NAME, MODE_WORLD_READABLE_COMPAT)
            // Migrate any pre-existing MODE_PRIVATE entries on first transition to world-readable LSPosed store
            if (!worldPrefs.contains("adblock_default_off_v1")) {
                try {
                    val privPrefs = context.getSharedPreferences(AD_BLOCKER_PREFS_NAME, Context.MODE_PRIVATE)
                    val privAll = privPrefs.all
                    if (privAll.isNotEmpty()) {
                        val editor = worldPrefs.edit()
                        copyPrefsMap(privAll, editor)
                        editor.apply()
                    }
                } catch (_: Throwable) {
                }
            }
            worldPrefs to true
        } catch (_: SecurityException) {
            context.getSharedPreferences(AD_BLOCKER_PREFS_NAME, Context.MODE_PRIVATE) to false
        } catch (_: Throwable) {
            context.getSharedPreferences(AD_BLOCKER_PREFS_NAME, Context.MODE_PRIVATE) to false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyPrefsMap(source: Map<String, *>, editor: SharedPreferences.Editor) {
        source.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
    }

    fun detectContainerRuntime(context: Context?): LSPosedRuntimeDetection {
        val selfHooked = isSelfHookActive()
        val hookedVariant = try {
            getHookedContainerName()
        } catch (_: Throwable) {
            ""
        }

        // 1. ClassLoader check: compileOnly stubs are NOT packaged in the APK.
        // Finding XposedBridge / libxposed at runtime proves the framework injected into this process.
        val xposedBridgeInjected = hasRuntimeClass("de.robv.android.xposed.XposedBridge")
        val modernLibXposedInjected = hasRuntimeClass("io.github.libxposed.api.XposedModule") ||
            hasRuntimeClass("org.lsposed.lspd.core.Startup")
        val classLoaderInjected = xposedBridgeInjected || modernLibXposedInjected

        // 2. Reflection / native method trampoline check on self-hookable status methods
        val methodMarkedNative = try {
            val m1 = DragonLSPosedStatus::class.java.getDeclaredMethod("isModuleActiveHooked")
            val m2 = DragonXposedModule::class.java.getDeclaredMethod("isModuleActiveHooked")
            Modifier.isNative(m1.modifiers) || Modifier.isNative(m2.modifiers)
        } catch (_: Throwable) {
            false
        }

        // 3. `/proc/self/maps` check for LSPosed / LSPatch / Zygisk / Riru libraries or memfd
        val mapsSignal = scanProcSelfMapsForLSPosed()

        // 4. LSPatch embedded container check (works rootless)
        val hasLspatchMetadata = checkLspatchSignals(context)

        // 5. World-readable XSharedPreferences support (LSPosed intercepts MODE_WORLD_READABLE only when active)
        val worldReadableSupported = if (context != null) {
            try {
                @Suppress("DEPRECATION")
                context.getSharedPreferences(AD_BLOCKER_PREFS_NAME, MODE_WORLD_READABLE_COMPAT)
                true
            } catch (_: SecurityException) {
                false
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }

        val apiVersion = getActiveXposedApiVersion()

        val inContainer = selfHooked ||
            classLoaderInjected ||
            methodMarkedNative ||
            mapsSignal != ProcMapSignal.NONE ||
            hasLspatchMetadata ||
            worldReadableSupported

        val containerType = when {
            hasLspatchMetadata || mapsSignal == ProcMapSignal.LSPATCH -> LSPosedContainerType.LSPATCH_CONTAINER
            mapsSignal == ProcMapSignal.LSPOSED_ZYGISK -> LSPosedContainerType.LSPOSED_ZYGISK
            mapsSignal == ProcMapSignal.LSPOSED_RIRU -> LSPosedContainerType.LSPOSED_RIRU
            selfHooked || classLoaderInjected || methodMarkedNative || worldReadableSupported -> {
                LSPosedContainerType.LSPOSED_HOOKED
            }
            mapsSignal == ProcMapSignal.XPOSED_LEGACY -> LSPosedContainerType.XPOSED_LEGACY
            else -> LSPosedContainerType.NONE
        }

        val effectiveVariantLabel = when {
            hookedVariant.isNotBlank() -> hookedVariant
            containerType != LSPosedContainerType.NONE -> containerType.label
            else -> LSPosedContainerType.NONE.label
        }

        val frameworkFilesPresent = checkFrameworkPathsExist()

        return LSPosedRuntimeDetection(
            isInContainer = inContainer,
            containerType = containerType,
            isSelfHookActive = selfHooked,
            isXposedClassLoaderInjected = classLoaderInjected,
            isNativeBridgeDetected = methodMarkedNative || mapsSignal != ProcMapSignal.NONE,
            isFrameworkFilesPresent = frameworkFilesPresent,
            apiVersion = if (apiVersion > 0) apiVersion else if (inContainer) 93 else 0,
            isWorldReadablePrefsSupported = worldReadableSupported,
            details = effectiveVariantLabel
        )
    }

    private fun hasRuntimeClass(name: String): Boolean {
        return try {
            Class.forName(name, false, DragonXposedModule::class.java.classLoader)
            true
        } catch (_: Throwable) {
            try {
                ClassLoader.getSystemClassLoader().loadClass(name)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private enum class ProcMapSignal {
        NONE,
        LSPOSED_ZYGISK,
        LSPOSED_RIRU,
        LSPATCH,
        XPOSED_LEGACY
    }

    private fun scanProcSelfMapsForLSPosed(): ProcMapSignal {
        return try {
            val mapsFile = File("/proc/self/maps")
            if (!mapsFile.canRead()) return ProcMapSignal.NONE
            var signal = ProcMapSignal.NONE
            mapsFile.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val lower = line.lowercase()
                    when {
                        lower.contains("lspatch") -> {
                            signal = ProcMapSignal.LSPATCH
                            break
                        }
                        lower.contains("zygisk_lsposed") || lower.contains("liblspd") || lower.contains("memfd:lspd") -> {
                            signal = ProcMapSignal.LSPOSED_ZYGISK
                            break
                        }
                        lower.contains("riru_lsposed") -> {
                            signal = ProcMapSignal.LSPOSED_RIRU
                            break
                        }
                        lower.contains("lsposed") || lower.contains("libxposed") -> {
                            signal = ProcMapSignal.LSPOSED_ZYGISK
                        }
                        lower.contains("xposed") && signal == ProcMapSignal.NONE -> {
                            signal = ProcMapSignal.XPOSED_LEGACY
                        }
                    }
                }
            }
            signal
        } catch (_: Throwable) {
            ProcMapSignal.NONE
        }
    }

    private fun checkLspatchSignals(context: Context?): Boolean {
        if (context == null) return false
        try {
            val appInfo = context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA
            )
            val meta = appInfo.metaData
            if (meta != null && (meta.containsKey("lspatch") || meta.containsKey("org.lsposed.lspatch"))) {
                return true
            }
        } catch (_: Throwable) {
        }
        return try {
            val assetList = context.assets.list("lspatch")
            assetList != null && assetList.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun checkFrameworkPathsExist(): Boolean {
        val candidatePaths = listOf(
            "/data/adb/lspd",
            "/data/adb/modules/zygisk_lsposed",
            "/data/adb/modules/riru_lsposed"
        )
        return candidatePaths.any {
            try {
                File(it).exists()
            } catch (_: Throwable) {
                false
            }
        }
    }
}
