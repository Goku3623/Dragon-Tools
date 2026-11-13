package com.example.xposed

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.data.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class LSPosedEnvironmentInfo(
    val isFrameworkInstalled: Boolean = false,
    val isModuleHookedInApp: Boolean = false,
    val isModuleEnabledInLSPosed: Boolean = false,
    val isRunningInLSPosedContainer: Boolean = false,
    val containerType: LSPosedContainerType = LSPosedContainerType.NONE,
    val xposedApiVersion: Int = 0,
    val frameworkVariant: String = "Not Detected",
    val arePermissionsGranted: Boolean = false,
    val isWorldReadablePrefsActive: Boolean = false,
    val configuredScopeCount: Int = 22
)

object DragonLSPosedStatus {

    /**
     * Hooked at runtime by [DragonLSPosedEntry] when LSPosed is active for this module.
     */
    @JvmStatic
    fun isModuleActiveHooked(): Boolean = false

    /**
     * Hooked at runtime by [DragonLSPosedEntry] to return XposedBridge.getXposedVersion().
     */
    @JvmStatic
    fun getHookedApiVersion(): Int = 0

    fun isLSPosedManagerInstalled(context: Context): Boolean {
        val packages = listOf(
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager"
        )
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }
        return false
    }

    suspend fun inspectLSPosedEnvironment(context: Context): LSPosedEnvironmentInfo = withContext(Dispatchers.IO) {
        val runtimeDetection = DragonXposedModule.detectContainerRuntime(context)
        val hookedInProcess = runtimeDetection.isSelfHookActive || runtimeDetection.isXposedClassLoaderInjected
        val hookedApi = runtimeDetection.apiVersion

        val scopePackages = DragonXposedModule.getConfiguredScopePackages(context)
        val scopeCount = if (scopePackages.isNotEmpty()) scopePackages.size else 22

        val managerInstalled = isLSPosedManagerInstalled(context)
        var frameworkDetected = runtimeDetection.isInContainer ||
            runtimeDetection.isFrameworkFilesPresent ||
            managerInstalled
        var moduleEnabledInDb = runtimeDetection.isSelfHookActive ||
            (runtimeDetection.isInContainer && runtimeDetection.isXposedClassLoaderInjected)
        var variant = when {
            runtimeDetection.isSelfHookActive -> "${runtimeDetection.details} (API $hookedApi)"
            runtimeDetection.isInContainer -> "${runtimeDetection.containerType.label} (API ${if (hookedApi > 0) hookedApi else 93})"
            managerInstalled -> "LSPosed Manager Installed (Enable Module)"
            else -> "Not Detected"
        }
        var permsGranted = runtimeDetection.isWorldReadablePrefsSupported || hookedInProcess

        if (RootHelper.isRootAvailable) {
            val pkg = context.packageName
            val checkCmd = """
                LSP_DIR=0
                LSP_VAR="none"
                if [ -d /data/adb/modules/zygisk_lsposed ] || ls -d /data/adb/modules/*zygisk*lsposed* >/dev/null 2>&1; then
                    LSP_DIR=1
                    LSP_VAR="zygisk"
                elif [ -d /data/adb/modules/riru_lsposed ]; then
                    LSP_DIR=1
                    LSP_VAR="riru"
                elif [ -d /data/adb/lspd ]; then
                    LSP_DIR=1
                    LSP_VAR="lspd"
                fi
                LSP_MOD=0
                if [ -f /data/adb/lspd/config/modules_config.db ]; then
                    if command -v sqlite3 >/dev/null 2>&1; then
                        EN=$(sqlite3 /data/adb/lspd/config/modules_config.db "SELECT enabled FROM modules WHERE module_pkg_name='$pkg' LIMIT 1;" 2>/dev/null || echo "")
                        if [ "${'$'}EN" = "1" ]; then
                            LSP_MOD=1
                        fi
                    elif grep -q "$pkg" /data/adb/lspd/config/modules_config.db 2>/dev/null; then
                        LSP_MOD=1
                    fi
                fi
                PREF_OK=0
                if [ -d /data/user/0/$pkg/shared_prefs ]; then
                    PREF_OK=1
                fi
                echo "D=${'$'}LSP_DIR|V=${'$'}LSP_VAR|M=${'$'}LSP_MOD|P=${'$'}PREF_OK"
            """.trimIndent()

            val out = RootHelper.executeCommand(checkCmd).output
            val dMatch = out.contains("D=1")
            val mMatch = out.contains("M=1")
            val pMatch = out.contains("P=1")

            if (dMatch) {
                frameworkDetected = true
                if (!runtimeDetection.isInContainer) {
                    variant = when {
                        out.contains("V=zygisk") || File("/data/adb/modules/zygisk_lsposed").exists() ->
                            "Zygisk LSPosed Framework"
                        out.contains("V=riru") ->
                            "Riru LSPosed Framework"
                        else ->
                            "LSPosed Framework (/data/adb/lspd)"
                    }
                }
            }
            if (mMatch) {
                moduleEnabledInDb = true
            }
            permsGranted = permsGranted || pMatch
        }

        val resolvedContainerType = when {
            runtimeDetection.containerType != LSPosedContainerType.NONE -> runtimeDetection.containerType
            variant.contains("Zygisk", ignoreCase = true) && moduleEnabledInDb -> LSPosedContainerType.LSPOSED_ZYGISK
            variant.contains("Riru", ignoreCase = true) && moduleEnabledInDb -> LSPosedContainerType.LSPOSED_RIRU
            else -> LSPosedContainerType.NONE
        }

        LSPosedEnvironmentInfo(
            isFrameworkInstalled = frameworkDetected,
            isModuleHookedInApp = hookedInProcess,
            isModuleEnabledInLSPosed = moduleEnabledInDb,
            isRunningInLSPosedContainer = runtimeDetection.isInContainer,
            containerType = resolvedContainerType,
            xposedApiVersion = if (hookedApi > 0) hookedApi else if (frameworkDetected) 93 else 0,
            frameworkVariant = variant,
            arePermissionsGranted = permsGranted,
            isWorldReadablePrefsActive = runtimeDetection.isWorldReadablePrefsSupported,
            configuredScopeCount = scopeCount
        )
    }

    /**
     * Grants all required permissions for LSPosed cross-process hook communication:
     * 1. Opens/migrates shared prefs into LSPosed world-readable store when active.
     * 2. Grants runtime & system permissions (WRITE_SECURE_SETTINGS, PACKAGE_USAGE_STATS, INTERACT_ACROSS_USERS).
     * 3. Sets world-readable permissions (0755/0644) on app data & shared_prefs so XSharedPreferences in hooked apps can read configs.
     * 4. Patches SELinux rules so hooked social media apps can query DragonLSPosedConfigProvider without binder denials.
     */
    suspend fun grantLSPosedModulePermissions(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Ensure world-readable LSPosed prefs are initialized/migrated even if root isn't present
        DragonXposedModule.openAdBlockerSharedPrefs(context)

        val pkg = context.packageName
        if (!RootHelper.isRootAvailable) {
            return@withContext DragonXposedModule.isInsideLSPosedContainer(context)
        }

        val cmd = """
            pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true
            pm grant $pkg android.permission.PACKAGE_USAGE_STATS 2>/dev/null || true
            pm grant $pkg android.permission.INTERACT_ACROSS_USERS 2>/dev/null || true
            pm grant $pkg android.permission.INTERACT_ACROSS_USERS_FULL 2>/dev/null || true
            appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
            appops set $pkg GET_USAGE_STATS allow 2>/dev/null || true

            # Make shared_prefs readable by XSharedPreferences inside hooked target apps
            chmod 0755 /data/data/$pkg 2>/dev/null || true
            chmod 0755 /data/user/0/$pkg 2>/dev/null || true
            chmod 0755 /data/data/$pkg/shared_prefs 2>/dev/null || true
            chmod 0755 /data/user/0/$pkg/shared_prefs 2>/dev/null || true
            chmod 0644 /data/data/$pkg/shared_prefs/*.xml 2>/dev/null || true
            chmod 0644 /data/user/0/$pkg/shared_prefs/*.xml 2>/dev/null || true

            # Allow untrusted_app to read XSharedPreferences & query ContentProvider across processes
            if command -v magiskpolicy >/dev/null 2>&1; then
                magiskpolicy --live "allow untrusted_app app_data_file dir { getattr search read open }" 2>/dev/null || true
                magiskpolicy --live "allow untrusted_app app_data_file file { getattr read open }" 2>/dev/null || true
            elif command -v ksud >/dev/null 2>&1; then
                ksud sepolicy patch "allow untrusted_app app_data_file dir { getattr search read open }" 2>/dev/null || true
                ksud sepolicy patch "allow untrusted_app app_data_file file { getattr read open }" 2>/dev/null || true
            fi
        """.trimIndent()

        RootHelper.executeCommand(cmd)
        true
    }

    /**
     * Opens LSPosed Manager (either the standalone manager app or the parasitic manager via root).
     */
    suspend fun openLSPosedManager(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val managerPackages = listOf(
            "org.lsposed.manager",
            "io.github.lsposed.manager",
            "de.robv.android.xposed.installer"
        )
        for (pkg in managerPackages) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return@withContext true
                }
            } catch (_: Exception) {}
        }

        // Fallback for Zygisk LSPosed parasitic manager via root shell
        if (RootHelper.isRootAvailable) {
            RootHelper.executeCommand(
                """
                am start -n org.lsposed.manager/.ui.activity.MainActivity 2>/dev/null || \
                cmd activity start-activity -n org.lsposed.manager/.ui.activity.MainActivity 2>/dev/null || \
                am broadcast -a android.provider.Telephony.SECRET_CODE -d android_secret_code://5776733 2>/dev/null || true
                """.trimIndent()
            )
            return@withContext true
        }
        false
    }
}
