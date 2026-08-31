package com.ai.assistance.operit.plugins.center

import java.io.File

enum class PluginHealthState {
    OK,
    ATTENTION,
    FAILED
}

data class PluginControlSnapshot(
    val plugin: PluginSnapshot,
    val health: PluginHealthState,
    val bindings: List<ExtensionBindingSnapshot>
)

class PluginControlPlane internal constructor(
    private val manager: PluginManager,
    private val extensionPoints: ExtensionPointRegistry,
    private val extensionRouter: ExtensionRouter
) {
    fun inspectPackage(sourcePackage: File): PluginManifest =
        PluginPackageInspector.inspect(sourcePackage)

    suspend fun install(
        sourcePackage: File,
        options: PluginInstallOptions = PluginInstallOptions()
    ): PluginInstallResult = manager.install(sourcePackage, options)

    suspend fun enable(pluginId: String): PluginPersistentState =
        manager.enable(pluginId)
    suspend fun disable(pluginId: String): PluginPersistentState =
        manager.disable(pluginId)

    suspend fun activateVersion(pluginId: String, version: String): PluginPersistentState =
        manager.activateVersion(pluginId, version)

    suspend fun rollback(pluginId: String): PluginPersistentState =
        manager.rollback(pluginId)

    suspend fun uninstall(pluginId: String, removeData: Boolean = false) {
        manager.uninstall(pluginId, removeData)
    }

    suspend fun restoreEnabledPlugins() {
        manager.restoreEnabledPlugins()
    }

    suspend fun shutdown() {
        manager.shutdown()
    }

    suspend fun snapshot(pluginId: String): PluginControlSnapshot =
        toControlSnapshot(manager.snapshot(pluginId))

    suspend fun snapshots(): List<PluginControlSnapshot> =
        manager.snapshots().map(::toControlSnapshot)
    fun extensionPointDefinitions(): List<ExtensionPointDefinition> =
        extensionPoints.list()

    fun extensionBindings(): List<ExtensionBindingSnapshot> =
        extensionRouter.listBindings()

    fun extensionBindings(pluginId: String): List<ExtensionBindingSnapshot> =
        extensionRouter.listBindingsByOwner(pluginId)

    private fun toControlSnapshot(plugin: PluginSnapshot): PluginControlSnapshot {
        val lifecycle = plugin.persistentState?.lastState
        val health = when (lifecycle) {
            PluginLifecycleState.FAILED,
            PluginLifecycleState.QUARANTINED -> PluginHealthState.FAILED
            PluginLifecycleState.MOUNTING,
            PluginLifecycleState.UNMOUNTING,
            PluginLifecycleState.PENDING_RESTART -> PluginHealthState.ATTENTION
            else -> PluginHealthState.OK
        }
        return PluginControlSnapshot(
            plugin = plugin,
            health = health,
            bindings = extensionRouter.listBindingsByOwner(plugin.pluginId)
        )
    }
}
