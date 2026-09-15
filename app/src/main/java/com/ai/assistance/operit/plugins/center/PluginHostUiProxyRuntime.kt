package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.core.tools.system.resident.ResidentHostRuntimeAttachment
import org.json.JSONObject

/**
 * Android Host-shell half of the Resident runtime split.
 *
 * This runtime owns only UI-side registries and presentation state. It deliberately has no
 * PluginManager, runtime adapters, ChildExtensionRuntime, capability registry, Bridge or Dispatcher.
 * Step 9 will feed these registries neutral snapshots/events from the BUSINESS owner. Until then
 * they are an empty, safe shell rather than a second copy of plugin business code.
 */
internal class PluginHostUiProxyRuntime(
    context: Context,
    attachment: ResidentHostRuntimeAttachment
) {
    @Volatile
    var attachment: ResidentHostRuntimeAttachment = attachment
        private set
    val runtimeRole: PluginRuntimeRole = PluginRuntimeRole.UI_PROXY
    val uiRegistry = PluginUiRegistry()
    val systemUiRegistry = SystemPluginUiRegistry(runtimeRole)
    val dynamicNavigationRegistry = DynamicNavigationSurfaceRegistry(context.applicationContext)
    val pagePresentationRegistry = PluginPagePresentationRegistry()

    fun updateAttachment(next: ResidentHostRuntimeAttachment) {
        check(next.usesUiProxy) { "UI proxy cannot transition to LEGACY_HOST in-process" }
        val currentSession = attachment.coreSessionId
        val nextSession = next.coreSessionId
        if (currentSession != null && nextSession != null) {
            check(currentSession == nextSession) {
                "Host UI proxy cannot switch Resident Core sessions without process restart"
            }
        }
        attachment = next
    }

    fun snapshot(): JSONObject = attachment.snapshot()
        .put("runtime_role", "ui_proxy")
        .put("business_runtime_restored", false)
        .put("owner_lease_held", false)
}

internal object PluginHostUiProxyRuntimeHolder {
    @Volatile
    private var runtime: PluginHostUiProxyRuntime? = null

    fun attach(context: Context, attachment: ResidentHostRuntimeAttachment): PluginHostUiProxyRuntime =
        synchronized(this) {
            check(attachment.usesUiProxy) { "LEGACY_HOST must not initialize the UI proxy runtime" }
            val existing = runtime
            if (existing != null) {
                existing.updateAttachment(attachment)
                return@synchronized existing
            }
            PluginHostUiProxyRuntime(context.applicationContext, attachment).also { runtime = it }
        }

    fun currentOrNull(): PluginHostUiProxyRuntime? = runtime

    fun requireRuntime(): PluginHostUiProxyRuntime =
        checkNotNull(runtime) { "Host UI proxy runtime is not attached" }
}
