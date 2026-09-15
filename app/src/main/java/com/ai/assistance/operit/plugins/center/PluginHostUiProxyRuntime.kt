package com.ai.assistance.operit.plugins.center

/**
 * Host-shell half of the Resident runtime split.
 *
 * This object may own Android UI object registries and presentation state, but deliberately has no
 * PluginManager, runtime adapters, ChildExtensionRuntime, capability registry, Bridge or Dispatcher.
 * Later UI-proxy work will feed it neutral snapshots/events from the BUSINESS owner instead of
 * mounting a second copy of plugin business code.
 */
internal class PluginHostUiProxyRuntime {
    val runtimeRole: PluginRuntimeRole = PluginRuntimeRole.UI_PROXY
    val systemUiRegistry = SystemPluginUiRegistry(runtimeRole)
    val pagePresentationRegistry = PluginPagePresentationRegistry()
}
