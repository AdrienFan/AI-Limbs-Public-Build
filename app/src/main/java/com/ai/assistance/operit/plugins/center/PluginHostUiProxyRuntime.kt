package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.core.tools.system.resident.ResidentHostRuntimeAttachment
import org.json.JSONObject

/**
 * Android Host-shell half of the Resident runtime split.
 *
 * This runtime owns only UI-side registries and presentation state. It deliberately has no
 * PluginManager, runtime adapters, ChildExtensionRuntime, capability registry, Bridge or Dispatcher.
 * Step 9 feeds these registries neutral snapshots/events from the BUSINESS owner and mounts only
 * Plugin Center's Host-side renderer. No plugin business runtime is restored in this process.
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
    private val residentClient = ResidentUiProxyClient(context.applicationContext, this)

    init {
        residentClient.start()
    }

    suspend fun invokeUiCapability(
        ownerPluginId: String,
        screenId: String,
        capabilityId: String,
        parameters: JSONObject
    ): JSONObject = residentClient.invokeUiCapability(ownerPluginId, screenId, capabilityId, parameters)

    suspend fun installPluginCenterRendererFromUri(
        uriText: String,
        originalName: String
    ): com.ai.assistance.operit.plugins.system.SystemPluginValidationResult =
        residentClient.installPluginCenterRendererFromUri(uriText, originalName)

    fun setActiveScreen(screenId: String?) = residentClient.setActiveScreen(screenId)

    fun setPresentationMode(ownerPluginId: String, screenId: String, mode: PluginPagePresentationMode) =
        residentClient.setPresentationMode(ownerPluginId, screenId, mode)

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
        residentClient.updateAttachment()
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
