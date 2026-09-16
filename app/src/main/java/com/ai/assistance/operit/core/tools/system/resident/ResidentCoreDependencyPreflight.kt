package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import com.ai.assistance.operit.core.application.OperitProcessContext
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences
import com.ai.assistance.operit.plugins.center.PluginRuntimeClassLoaders
import com.ai.assistance.operit.plugins.center.PluginStore
import com.ai.assistance.operit.plugins.center.PluginStateRepository
import com.ai.assistance.operit.plugins.center.PluginTrustKeyringV1
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only process-dependency gate that must pass before Host business retirement can begin.
 *
 * This intentionally does not acquire the plugin_kernel lease or mount plugins. It proves that the
 * standalone app_process Core has the process-local dependencies that previously came only from
 * OperitApplication.onCreate(), and that its trust/plugin storage can be read before takeover.
 */
internal object ResidentCoreDependencyPreflight {
    fun run(context: Context): JSONObject {
        val appContext = context.applicationContext
        check(appContext === context) {
            "Resident Core dependency preflight requires the standalone application Context"
        }

        val security = ResidentCoreSecurityBootstrap.initialize(appContext)
        OperitProcessContext.initialize(appContext)
        AppLogger.bindContext(appContext)
        check(OperitProcessContext.require().filesDir.canonicalFile == appContext.filesDir.canonicalFile) {
            "Resident Core process Context registry does not match app Context"
        }

        // The build31 real-device failure happened because this global existed only in Host
        // Application.onCreate(). Initialize it explicitly and force real DataStore reads here.
        initAndroidPermissionPreferences(appContext)
        // app_process does not execute OperitApplication.onCreate(). Shell consumers in every
        // bridge/subsystem must use this process Context and the normal selected backend.
        AndroidShellExecutor.setContext(appContext)
        AndroidShellExecutor.clearPreferredPermissionLevelCache()
        val preferredPermission = androidPermissionPreferences.getPreferredPermissionLevel()
        val rootMode = androidPermissionPreferences.getRootExecutionMode()
        val customSu = androidPermissionPreferences.getCustomSuCommand()
        check(customSu.isNotBlank()) { "Android permission custom su command resolved blank" }

        // Trust verification is also business-process state. Force the keyring to resolve through
        // the process Context now rather than discovering a Host-only Application dependency later.
        val keyring = PluginTrustKeyringV1.current()
        check(keyring.version > 0 && keyring.signers.isNotEmpty()) {
            "Plugin trust keyring is not readable in Resident Core"
        }

        // Code identity is independent from Android Context identity. Resident Core is launched by
        // app_process, while its standalone ContextImpl owns a LoadedApk ClassLoader. Prove that the
        // BUSINESS plugin ABI resolves through one canonical defining loader before Host retires.
        val businessAbiLoader = PluginRuntimeClassLoaders.businessAbi()

        // app_process does not inherit the APK's native search path from a normal Zygote-bound
        // application process. Prove the installed native directory and the ToolPkg QuickJS JNI
        // bridge before Host retirement so a loader regression rejects takeover safely.
        val nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir?.trim().orEmpty()
        check(nativeLibraryDir.isNotBlank() && java.io.File(nativeLibraryDir).isDirectory) {
            "Resident Core native library directory is unavailable: $nativeLibraryDir"
        }
        runCatching {
            Class.forName(
                "com.ai.assistance.operit.core.tools.javascript.QuickJsNativeBridge",
                true,
                ResidentCoreDependencyPreflight::class.java.classLoader
            )
        }.getOrElse { error ->
            throw IllegalStateException(
                "Resident Core JNI preflight failed for quickjsjni in $nativeLibraryDir",
                error
            )
        }

        val store = PluginStore.fromContext(appContext)
        val installed = store.listPluginIds()
        check(store.rootDir.canonicalPath.startsWith(appContext.filesDir.canonicalPath + java.io.File.separator)) {
            "Plugin store resolved outside application filesDir"
        }

        // Refuse an old Bridge before disconnecting the Host. The readiness contract is additive;
        // older Bridge builds remain usable in ordinary Host mode but cannot prove Core takeover.
        val bridgeId = "plugin.system.bridge"
        val stateRepository = PluginStateRepository(store)
        val bridge = stateRepository.read(bridgeId)
        if (bridge?.enabled == true) {
            val version = checkNotNull(bridge.activeVersion) { "Enabled Bridge has no active version" }
            val manifest = stateRepository.readInstalledManifest(bridgeId, version)
            check("plugin.bridge.runtime_readiness.v1" in manifest.provides.providers) {
                "Resident requires Bridge 1.3.10 or newer with runtime readiness; update Bridge before enabling Resident"
            }
        }

        return JSONObject()
            .put("ready", true)
            .put("security", security)
            .put("bridge_readiness_contract_ready", true)
            .put("process_context_ready", true)
            .put("permission_preferences_ready", true)
            .put("android_shell_context_ready", true)
            .put("preferred_permission_level", preferredPermission?.name ?: JSONObject.NULL)
            .put("root_execution_mode", rootMode.name)
            .put("custom_su_configured", customSu.isNotBlank())
            .put("trust_keyring_ready", true)
            .put("trust_keyring_version", keyring.version)
            .put("business_abi_loader_ready", true)
            .put("business_abi_loader", businessAbiLoader.javaClass.name)
            .put("context_loader_is_business_abi_loader", appContext.classLoader === businessAbiLoader)
            .put("native_library_dir_ready", true)
            .put("native_library_dir", nativeLibraryDir)
            .put("quickjs_jni_ready", true)
            .put("plugin_store_ready", true)
            .put("installed_plugin_count", installed.size)
            .put("installed_plugin_ids", JSONArray(installed))
    }
}
