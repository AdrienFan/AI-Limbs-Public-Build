package com.ai.assistance.operit.core.tools.system.resident

import android.content.Context
import com.ai.assistance.operit.core.application.OperitProcessContext
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.data.preferences.initAndroidPermissionPreferences
import com.ai.assistance.operit.plugins.center.PluginRuntimeClassLoaders
import com.ai.assistance.operit.plugins.center.PluginStore
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

        OperitProcessContext.initialize(appContext)
        AppLogger.bindContext(appContext)
        check(OperitProcessContext.require().filesDir.canonicalFile == appContext.filesDir.canonicalFile) {
            "Resident Core process Context registry does not match app Context"
        }

        // The build31 real-device failure happened because this global existed only in Host
        // Application.onCreate(). Initialize it explicitly and force real DataStore reads here.
        initAndroidPermissionPreferences(appContext)
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

        val store = PluginStore.fromContext(appContext)
        val installed = store.listPluginIds()
        check(store.rootDir.canonicalPath.startsWith(appContext.filesDir.canonicalPath + java.io.File.separator)) {
            "Plugin store resolved outside application filesDir"
        }

        return JSONObject()
            .put("ready", true)
            .put("process_context_ready", true)
            .put("permission_preferences_ready", true)
            .put("preferred_permission_level", preferredPermission?.name ?: JSONObject.NULL)
            .put("root_execution_mode", rootMode.name)
            .put("custom_su_configured", customSu.isNotBlank())
            .put("trust_keyring_ready", true)
            .put("trust_keyring_version", keyring.version)
            .put("business_abi_loader_ready", true)
            .put("business_abi_loader", businessAbiLoader.javaClass.name)
            .put("context_loader_is_business_abi_loader", appContext.classLoader === businessAbiLoader)
            .put("plugin_store_ready", true)
            .put("installed_plugin_count", installed.size)
            .put("installed_plugin_ids", JSONArray(installed))
    }
}
