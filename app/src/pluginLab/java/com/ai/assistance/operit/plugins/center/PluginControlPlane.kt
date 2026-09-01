package com.ai.assistance.operit.plugins.center

import com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessSystemIds
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
    private val extensionRouter: ExtensionRouter,
    private val contributions: PluginContributionRegistry,
    private val surfacePolicy: HostSurfacePolicy,
    private val inactivityPolicy: PluginInactivityPolicyStore,
    private val backupPolicy: PluginBackupPolicyStore,
    private val onInactivityPolicyChanged: () -> Unit = {}
) {
    fun inspectPackage(sourcePackage: File): PluginManifest =
        PluginPackageInspector.inspect(sourcePackage)

    suspend fun install(
        sourcePackage: File,
        options: PluginInstallOptions = PluginInstallOptions()
    ): PluginInstallResult = manager.install(sourcePackage, options)

    suspend fun enable(pluginId: String): PluginPersistentState =
        manager.enable(pluginId)
    suspend fun disable(
        pluginId: String,
        adminAuthorized: Boolean = false
    ): PluginPersistentState =
        manager.disable(pluginId, adminAuthorized)

    suspend fun activateVersion(pluginId: String, version: String): PluginPersistentState =
        manager.activateVersion(pluginId, version)

    suspend fun rollback(pluginId: String): PluginPersistentState =
        manager.rollback(pluginId)

    suspend fun uninstall(
        pluginId: String,
        removeData: Boolean = false,
        adminAuthorized: Boolean = false
    ) {
        manager.uninstall(pluginId, removeData, adminAuthorized)
    }

    suspend fun restoreEnabledPlugins() {
        manager.restoreEnabledPlugins()
    }

    fun developerModeEnabled(): Boolean = surfacePolicy.developerMode

    fun hostSurfaceSnapshots(): List<HostSurfaceSnapshot> = surfacePolicy.snapshots()

    suspend fun setDeveloperMode(enabled: Boolean) {
        surfacePolicy.setDeveloperMode(enabled)
    }

    suspend fun setHostSurfaceAllowed(surfaceId: String, allowed: Boolean) {
        surfacePolicy.setAllowed(surfaceId, allowed)
        manager.reconcileHostSurfacePolicy()
    }

    suspend fun setHostSurfacesAllowed(surfaceIds: Collection<String>, allowed: Boolean) {
        surfacePolicy.setAllowed(surfaceIds, allowed)
        manager.reconcileHostSurfacePolicy()
    }

    fun inactivityPolicySnapshot(): PluginInactivityPolicySnapshot = inactivityPolicy.snapshot()

    suspend fun configureInactivityPolicy(
        enabled: Boolean,
        mode: InactivityThresholdMode,
        days: Int,
        testSeconds: Int
    ) {
        inactivityPolicy.configure(enabled, mode, days, testSeconds)
        manager.reconcileInactivityPolicy()
        onInactivityPolicyChanged()
    }

    suspend fun runInactivityCheck() {
        manager.reconcileInactivityPolicy()
    }

    fun backupPolicySnapshot(): PluginBackupPolicySnapshot = backupPolicy.snapshot()

    suspend fun configureBackupPolicy(enabled: Boolean) {
        backupPolicy.configure(enabled)
        manager.reconcileBackupPolicy()
        extensionHub()?.setAutoBackupPolicy(enabled, PluginBackupPolicyStore.HIGH_FREQUENCY_USE_COUNT)
    }

    suspend fun backup(pluginId: String): PluginBackupSnapshot = manager.backup(pluginId)

    suspend fun restoreBackup(pluginId: String): PluginPersistentState = manager.restoreBackup(pluginId)

    suspend fun deleteBackup(pluginId: String) {
        manager.deleteBackup(pluginId)
    }

    suspend fun backupSnapshots(): List<PluginBackupSnapshot> = manager.backupSnapshots()

    suspend fun runBackupCheck() {
        manager.reconcileBackupPolicy()
        extensionHub()?.setAutoBackupPolicy(
            backupPolicy.snapshot().enabled,
            PluginBackupPolicyStore.HIGH_FREQUENCY_USE_COUNT
        )
    }

    fun childBackupSnapshots(): List<ChildExtensionBackupSnapshot> =
        extensionHub()?.backupSnapshots()?.value.orEmpty()

    suspend fun restoreChildBackup(extensionId: String) =
        extensionHub()?.restoreBackup(extensionId)
            ?: throw PluginInstallException("EXTENSION_HUB_UNAVAILABLE", "Plugin Extension Hub is not active")

    suspend fun deleteChildBackup(extensionId: String): Boolean =
        extensionHub()?.deleteBackup(extensionId)
            ?: throw PluginInstallException("EXTENSION_HUB_UNAVAILABLE", "Plugin Extension Hub is not active")

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

    private fun extensionHub(): ExtensionHubService? =
        contributions.find(PluginContributionKind.PROVIDER, InProcessSystemIds.EXTENSION_HUB_PROVIDER)
            ?.payload as? ExtensionHubService

    private fun toControlSnapshot(plugin: PluginSnapshot): PluginControlSnapshot {
        val lifecycle = plugin.persistentState?.lastState
        val health = when (lifecycle) {
            PluginLifecycleState.FAILED,
            PluginLifecycleState.QUARANTINED -> PluginHealthState.FAILED
            PluginLifecycleState.MOUNTING,
            PluginLifecycleState.UNMOUNTING,
            PluginLifecycleState.PENDING_RESTART,
            PluginLifecycleState.BLOCKED -> PluginHealthState.ATTENTION
            else -> PluginHealthState.OK
        }
        return PluginControlSnapshot(
            plugin = plugin,
            health = health,
            bindings = extensionRouter.listBindingsByOwner(plugin.pluginId)
        )
    }
}
