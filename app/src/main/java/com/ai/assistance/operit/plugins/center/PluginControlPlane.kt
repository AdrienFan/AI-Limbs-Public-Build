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
    private val extensionRouter: ExtensionRouter,
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

    fun hostPrimitiveSnapshots(): List<HostPrimitiveSnapshot> =
        AiLimbsHostPrimitiveCatalog.snapshots(surfacePolicy)

    suspend fun setHostPrimitiveAllowed(primitiveId: String, allowed: Boolean) {
        val primitive = AiLimbsHostPrimitiveCatalog.find(primitiveId)
            ?: throw PluginInstallException("HOST_PRIMITIVE_UNKNOWN", "Unknown AI Limbs Host Primitive: $primitiveId")
        if (!primitive.requestableScope || primitive.exposure != HostPrimitiveExposure.BOUND) {
            throw PluginInstallException(
                "HOST_PRIMITIVE_NOT_TOGGLEABLE",
                "Host Primitive policy is not user-toggleable in this kernel build: ${primitive.id} (${primitive.exposure})"
            )
        }
        surfacePolicy.setAllowed(PluginSurfaceIds.hostPrimitive(primitive.id), allowed)
        manager.reconcileHostSurfacePolicy()
    }

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
    }

    suspend fun backup(pluginId: String): PluginBackupSnapshot = manager.backup(pluginId)

    suspend fun restoreBackup(pluginId: String): PluginPersistentState = manager.restoreBackup(pluginId)

    suspend fun deleteBackup(pluginId: String) {
        manager.deleteBackup(pluginId)
    }

    suspend fun backupSnapshots(): List<PluginBackupSnapshot> = manager.backupSnapshots()

    suspend fun runBackupCheck() {
        manager.reconcileBackupPolicy()
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
