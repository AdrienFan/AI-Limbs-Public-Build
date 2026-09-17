package com.ai.assistance.operit.plugins.center.isolation

import android.content.Context
import com.ai.assistance.operit.plugins.center.AndroidInProcessPluginRuntimeAdapter
import com.ai.assistance.operit.plugins.center.BusinessPageProviderMetadata
import com.ai.assistance.operit.plugins.center.ChildExtensionRuntime
import com.ai.assistance.operit.plugins.center.ExtensionPointDefinition
import com.ai.assistance.operit.plugins.center.ExtensionPointRegistry
import com.ai.assistance.operit.plugins.center.ExtensionRouter
import com.ai.assistance.operit.plugins.center.HostSurfaceDefinition
import com.ai.assistance.operit.plugins.center.HostSurfaceKind
import com.ai.assistance.operit.plugins.center.HostSurfacePolicy
import com.ai.assistance.operit.plugins.center.NoApprovedPluginSecretBroker
import com.ai.assistance.operit.plugins.center.OfficialPluginIdentityRegistry
import com.ai.assistance.operit.plugins.center.PluginCapabilityDomain
import com.ai.assistance.operit.plugins.center.PluginCapabilityEffect
import com.ai.assistance.operit.plugins.center.PluginCapabilityParameterSpec
import com.ai.assistance.operit.plugins.center.PluginCapabilityReceipt
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.PluginContributionKind
import com.ai.assistance.operit.plugins.center.PluginContributionRecord
import com.ai.assistance.operit.plugins.center.PluginContributionRegistry
import com.ai.assistance.operit.plugins.center.PluginContextFactory
import com.ai.assistance.operit.plugins.center.PluginEventBusHost
import com.ai.assistance.operit.plugins.center.PluginExtensionPoints
import com.ai.assistance.operit.plugins.center.PluginHomeTileSpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginLocalModelLoaderSpec
import com.ai.assistance.operit.plugins.center.PluginMountScope
import com.ai.assistance.operit.plugins.center.PluginRuntimeAdapterContext
import com.ai.assistance.operit.plugins.center.PluginRuntimeHost
import com.ai.assistance.operit.plugins.center.PluginRuntimeRole
import com.ai.assistance.operit.plugins.center.PluginScreenSpec
import com.ai.assistance.operit.plugins.center.PluginStateRepository
import com.ai.assistance.operit.plugins.center.PluginStore
import com.ai.assistance.operit.plugins.center.PluginSurfaceIds
import com.ai.assistance.operit.plugins.center.PluginThemeSpec
import com.ai.assistance.operit.plugins.center.HostedPluginRuntime
import com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.ChildUiContributionSnapshot
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessPageProvider
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Execution-only dynamic plugin runtime.
 *
 * Core remains the only PluginManager/persistent-state owner. This process only loads trusted
 * android_inprocess payloads and child extensions, so Dex/JNI faults cannot kill Resident Core.
 */
internal class PluginWorkerRuntime(
    private val appContext: Context,
    ownerCorePid: Int,
    ownerCoreSession: String
) {
    private data class Mount(
        val version: String,
        val runtime: HostedPluginRuntime,
        val ownerBinding: AutoCloseable
    )

    private val store = PluginStore.fromContext(appContext)
    private val stateRepository = PluginStateRepository(store)
    private val surfacePolicy = HostSurfacePolicy(appContext)
    private val contributions = PluginContributionRegistry()
    private val coreClient = PluginRuntimeCoreClient(ownerCorePid, ownerCoreSession)
    private val capabilityGateway = PluginWorkerCapabilityGateway(coreClient)
    private val notificationRegistry = PluginWorkerNotificationRegistry()
    private val identityRegistry = OfficialPluginIdentityRegistry(appContext)
    private val extensionPoints = ExtensionPointRegistry()
    private val extensionRouter: ExtensionRouter
    private val childRuntime: ChildExtensionRuntime
    private val contextFactory: PluginContextFactory
    private val adapter: AndroidInProcessPluginRuntimeAdapter
    private val runtimeHost = PluginRuntimeHost(PluginRuntimeRole.BUSINESS)
    private val mounts = ConcurrentHashMap<String, Mount>()

    init {
        registerSurfaces()
        registerExtensionPoints()
        extensionRouter = ExtensionRouter(extensionPoints, surfacePolicy)
        childRuntime = ChildExtensionRuntime(
            appContext = appContext,
            runtimeRole = PluginRuntimeRole.BUSINESS,
            pluginStore = store,
            contributions = contributions,
            capabilityRegistry = capabilityGateway
        )
        contextFactory = PluginContextFactory(
            contributions = contributions,
            eventBusHost = PluginEventBusHost(),
            capabilityInvokerFactory = capabilityGateway,
            secretBroker = NoApprovedPluginSecretBroker,
            surfacePolicy = surfacePolicy,
            serviceResolverFactory = { manifest, scopes, lease ->
                PluginWorkerServiceResolver(
                    manifest = manifest,
                    version = manifest.version,
                    grantedScopes = scopes,
                    contributions = contributions,
                    surfacePolicy = surfacePolicy,
                    callerLease = lease,
                    coreClient = coreClient
                )
            }
        )
        adapter = AndroidInProcessPluginRuntimeAdapter(
            contributions = contributions,
            notificationBindingProvider = notificationRegistry::bindingFor,
            identityRegistry = identityRegistry,
            childRuntimeProvider = { childRuntime }
        )
    }

    @Volatile private var childStarted = false

    suspend fun start() {
        startChildren()
    }

    suspend fun startChildren(): JSONObject {
        if (!childStarted) {
            childRuntime.start()
            childStarted = true
        }
        return JSONObject().put("started", true)
    }

    suspend fun awaitEnabledPointReady(point: String, timeoutMs: Long): JSONObject {
        require(point.isNotBlank()) { "Child extension point is blank" }
        require(timeoutMs in 1..PluginRuntimeWire.BUSINESS_TIMEOUT_MS.toLong()) {
            "Invalid child readiness timeout: $timeoutMs"
        }
        childRuntime.awaitEnabledPointReady(point, timeoutMs)
        return JSONObject().put("ready", true).put("point", point)
    }

    suspend fun awaitBusinessChildrenReady(timeoutMs: Long): JSONObject {
        require(timeoutMs in 1..PluginRuntimeWire.BUSINESS_TIMEOUT_MS.toLong()) {
            "Invalid child readiness timeout: $timeoutMs"
        }
        return childRuntime.awaitBusinessChildrenReady(timeoutMs)
    }

    suspend fun stopChildren(): JSONObject {
        if (childStarted) {
            childRuntime.stop()
            childStarted = false
        }
        return JSONObject().put("stopped", true)
    }

    suspend fun stop() {
        val failures = mutableListOf<Throwable>()
        runCatching { stopChildren() }.exceptionOrNull()?.let(failures::add)
        mounts.keys.toList().sortedDescending().forEach { pluginId ->
            runCatching { stopPlugin(pluginId, ownerShutdown = true) }.exceptionOrNull()?.let(failures::add)
        }
        notificationRegistry.close()
        if (failures.isNotEmpty()) {
            val failure = IllegalStateException("Plugin worker retirement failed")
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    suspend fun mount(pluginId: String, version: String): JSONObject {
        require(pluginId.isNotBlank() && version.isNotBlank())
        mounts[pluginId]?.let { current ->
            if (current.version == version) return snapshot(pluginId)
            stopPlugin(pluginId)
        }
        val state = stateRepository.read(pluginId)
            ?: throw PluginInstallException("PLUGIN_WORKER_STATE_MISSING", "Plugin state is missing: $pluginId")
        check(state.activeVersion == version) { "Worker version does not match Core active version" }
        val manifest = stateRepository.readInstalledManifest(pluginId, version)
        check(manifest.pluginId == pluginId && manifest.version == version)
        check(manifest.runtime.kind == "android_inprocess") {
            "Worker accepts android_inprocess only: ${manifest.runtime.kind}"
        }
        val metadata = stateRepository.readInstallMetadata(pluginId, version)
            ?: throw PluginInstallException("PLUGIN_WORKER_METADATA_MISSING", "Install metadata is missing")
        check(metadata.grantedScopes == manifest.permissions.requestedScopes) {
            "Worker scope approval does not match manifest"
        }
        check(identityRegistry.isTrusted(manifest, metadata)) {
            "Worker refused untrusted android_inprocess identity: $pluginId"
        }
        surfacePolicy.requireManifestAllowed(manifest)

        val ownerBinding = capabilityGateway.bindTopLevel(pluginId, version)
        val mountScope = PluginMountScope(
            manifest,
            contributions,
            extensionRouter,
            capabilityGateway,
            surfacePolicy
        )
        var hosted: HostedPluginRuntime? = null
        try {
            val versionDir = store.versionDir(pluginId, version)
            val dataDir = store.dataDir(pluginId).apply { mkdirs() }
            val cacheDir = store.cacheDir(pluginId).apply { mkdirs() }
            val payloadContext = contextFactory.create(
                manifest = manifest,
                mountScope = mountScope,
                dataDir = dataDir,
                cacheDir = cacheDir,
                grantedScopes = metadata.grantedScopes
            )
            hosted = runtimeHost.mount(
                adapter = adapter,
                context = PluginRuntimeAdapterContext(
                    runtimeRole = PluginRuntimeRole.BUSINESS,
                    appContext = appContext,
                    manifest = manifest,
                    versionDir = versionDir,
                    contentDir = store.contentIn(versionDir),
                    dataDir = dataDir,
                    cacheDir = cacheDir,
                    installMetadata = metadata,
                    payloadContext = payloadContext
                ),
                scope = mountScope
            )
            val activeRuntime = checkNotNull(hosted)
            mounts[pluginId] = Mount(version, activeRuntime, ownerBinding)
            return snapshot(pluginId)
        } catch (error: Throwable) {
            val activeRuntime = hosted
            if (activeRuntime != null) {
                mounts.remove(pluginId)
                runCatching { runtimeHost.stop(activeRuntime) }
                    .exceptionOrNull()?.let(error::addSuppressed)
            }
            ownerBinding.close()
            notificationRegistry.clearOwner(pluginId)
            throw error
        }
    }

    suspend fun stopPlugin(pluginId: String, ownerShutdown: Boolean = false): JSONObject {
        val mount = mounts.remove(pluginId)
            ?: return JSONObject().put("stopped", true).put("plugin_id", pluginId)
        val result = runtimeHost.stop(mount.runtime, ownerShutdown = ownerShutdown)
        mount.ownerBinding.close()
        notificationRegistry.clearOwner(pluginId)
        if (!result.stoppedCleanly) {
            throw PluginInstallException(
                result.errorCode ?: "PLUGIN_WORKER_STOP_FAILED",
                result.message ?: "Worker plugin did not stop cleanly: $pluginId"
            )
        }
        return JSONObject().put("stopped", true).put("plugin_id", pluginId)
    }

    suspend fun invokeCapability(pluginId: String, capabilityId: String, parameters: JSONObject): JSONObject =
        capabilityGateway.executeLocal(pluginId, capabilityId, parameters)

    suspend fun invokeProviderExecutor(
        pluginId: String,
        providerId: String,
        parametersJson: String
    ): String {
        val record = requireProvider(pluginId, providerId)
        val executor = record.payload as? InProcessCapabilityExecutor
            ?: throw PluginInstallException("WORKER_PROVIDER_NOT_EXECUTOR", "Provider is not an executor: $providerId")
        return executor.invoke(parametersJson)
    }

    suspend fun performUiProvider(
        pluginId: String,
        providerId: String,
        eventId: String,
        payloadJson: String
    ): String {
        val record = requireProvider(pluginId, providerId)
        val provider = record.payload as? InProcessUiStateProvider
            ?: throw PluginInstallException("WORKER_PROVIDER_NOT_UI_STATE", "Provider is not UI state: $providerId")
        return provider.perform(eventId, payloadJson)
    }

    suspend fun performNotification(pluginId: String, actionId: String): Boolean =
        notificationRegistry.perform(pluginId, actionId)

    fun snapshot(pluginId: String): JSONObject {
        val mount = mounts[pluginId]
            ?: throw PluginInstallException("PLUGIN_WORKER_NOT_MOUNTED", "Worker plugin is not mounted: $pluginId")
        return JSONObject()
            .put("plugin_id", pluginId)
            .put("version", mount.version)
            .put("capabilities", capabilityGateway.descriptors(pluginId))
            .put("extensions", extensionDescriptors(pluginId))
            .put("providers", providerDescriptors(pluginId))
            .put("notification", notificationRegistry.snapshot(pluginId))
    }

    fun childSnapshot(): JSONObject {
        val children = childRuntime.loggingSnapshots()
        val childIds = children.mapTo(linkedSetOf()) { it.extensionId }
        val childCapabilities = JSONArray().apply {
            val all = capabilityGateway.descriptors()
            for (index in 0 until all.length()) {
                val descriptor = all.getJSONObject(index)
                if (descriptor.optString("owner_id") in childIds) put(descriptor)
            }
        }
        return JSONObject()
            .put("runtime", childRuntime.businessRuntimeSnapshot())
            .put("presentations", childRuntime.residentPresentationDescriptors())
            .put("children", JSONArray().apply { children.forEach { put(childSnapshotJson(it)) } })
            .put("backups", JSONArray().apply { childRuntime.loggingBackupSnapshots().forEach { put(childBackupJson(it)) } })
            .put("ui_contributions", JSONArray().apply {
                childRuntime.loggingUiContributions().forEach { contribution ->
                    put(JSONObject()
                        .put("extension_id", contribution.extensionId)
                        .put("parent_plugin_id", contribution.target.parentPluginId)
                        .put("point", contribution.target.point)
                        .put("api_version", contribution.target.apiVersion)
                        .put("screen_id", contribution.screenId)
                        .put("component_id", contribution.componentId)
                        .put("slot_id", contribution.slotId)
                        .put("contribution_id", contribution.contributionId)
                        .put("document_json", contribution.provider.documentJson.value ?: JSONObject.NULL))
                }
            })
            .put("capabilities", childCapabilities)
    }

    suspend fun childControl(operation: String, payload: JSONObject): JSONObject {
        val runtime = childRuntime.bound(InProcessSystemIds.PLUGIN_CENTER_PLUGIN_ID, emptySet())
        val extensionId = payload.optString("extension_id").trim()
        return when (operation) {
            "uninstall" -> JSONObject().put("removed", runtime.uninstall(extensionId))
            "set_enabled" -> childSnapshotJson(runtime.setEnabled(extensionId, payload.getBoolean("enabled")))
            "backup" -> childBackupJson(runtime.backup(extensionId))
            "restore_backup" -> childSnapshotJson(runtime.restoreBackup(extensionId))
            "delete_backup" -> JSONObject().put("deleted", runtime.deleteBackup(extensionId))
            "set_auto_backup_policy" -> {
                runtime.setAutoBackupPolicy(payload.getBoolean("enabled"), payload.optLong("high_frequency_use_count", 10L))
                JSONObject().put("ok", true)
            }
            "record_use" -> {
                runtime.recordUse(extensionId)
                JSONObject().put("ok", true)
            }
            else -> throw PluginInstallException("WORKER_CHILD_OPERATION_UNKNOWN", "Unknown child operation: $operation")
        }
    }

    suspend fun installChild(packagePath: String, expectedParentPluginId: String?, expectedPoint: String?): JSONObject {
        val record = contributions.find(PluginContributionKind.PROVIDER, InProcessSystemIds.EXTENSION_HUB_PROVIDER)
            ?: throw PluginInstallException("WORKER_EXTENSION_HUB_UNAVAILABLE", "Extension Hub provider is not active")
        val service = record.payload as? ExtensionHubService
            ?: throw PluginInstallException("WORKER_EXTENSION_HUB_INVALID", "Extension Hub provider has incompatible payload")
        return childSnapshotJson(service.install(java.io.File(packagePath), expectedParentPluginId, expectedPoint))
    }

    suspend fun performChildUi(
        extensionId: String,
        contributionId: String,
        eventId: String,
        payloadJson: String
    ): String {
        val contribution = childRuntime.loggingUiContributions().firstOrNull {
            it.extensionId == extensionId && it.contributionId == contributionId
        } ?: throw PluginInstallException("WORKER_CHILD_UI_UNKNOWN", "Child UI contribution is not active")
        return contribution.provider.perform(eventId, payloadJson)
    }

    suspend fun invokeChildPresentation(extensionId: String, command: String, parameters: JSONObject): JSONObject =
        childRuntime.invokePresentationCommand(extensionId, command, parameters)

    suspend fun exportChildBackups(extensionIds: Collection<String>, treeUri: String): List<String> =
        childRuntime.exportBackups(extensionIds, treeUri)

    private fun childSnapshotJson(value: ChildExtensionSnapshot): JSONObject = JSONObject()
        .put("extension_id", value.extensionId)
        .put("version", value.version)
        .put("display_name", value.displayName)
        .put("description", value.description ?: JSONObject.NULL)
        .put("parent_plugin_id", value.target.parentPluginId)
        .put("point", value.target.point)
        .put("api_version", value.target.apiVersion)
        .put("lifecycle", value.lifecycle.name.lowercase())
        .put("enabled", value.enabled)
        .put("roles", JSONArray(value.roles.toList().sorted()))
        .put("use_count", value.useCount)
        .put("last_error", value.lastError ?: JSONObject.NULL)

    private fun childBackupJson(value: ChildExtensionBackupSnapshot): JSONObject = JSONObject()
        .put("extension_id", value.extensionId)
        .put("version", value.version)
        .put("display_name", value.displayName)
        .put("description", value.description ?: JSONObject.NULL)
        .put("parent_plugin_id", value.target.parentPluginId)
        .put("point", value.target.point)
        .put("api_version", value.target.apiVersion)
        .put("roles", JSONArray(value.roles.toList().sorted()))
        .put("package_sha256", value.packageSha256)
        .put("backed_up_at", value.backedUpAtEpochMs)
        .put("was_enabled", value.wasEnabled)
        .put("installed", value.installed)
        .put("installed_version", value.installedVersion ?: JSONObject.NULL)

    private fun requireProvider(pluginId: String, providerId: String): PluginContributionRecord {
        val record = contributions.find(PluginContributionKind.PROVIDER, providerId)
            ?: throw PluginInstallException("WORKER_PROVIDER_UNKNOWN", "Provider is not active: $providerId")
        check(record.ownerPluginId == pluginId) { "Worker provider owner mismatch: $providerId" }
        return record
    }

    private fun providerDescriptors(pluginId: String): JSONArray = JSONArray().apply {
        contributions.listByOwner(pluginId)
            .filter { it.kind == PluginContributionKind.PROVIDER }
            .sortedBy { it.id }
            .forEach { record ->
                val base = JSONObject()
                    .put("id", record.id)
                    .put("metadata", JSONObject(record.metadata))
                when (val payload = record.payload) {
                    is InProcessUiStateProvider -> put(
                        base.put("kind", "ui_state")
                            .put("state_json", payload.stateJson.value ?: JSONObject.NULL)
                    )
                    is InProcessCapabilityExecutor -> put(base.put("kind", "capability_executor"))
                    is InProcessPageProvider -> put(base.put("kind", "page_local"))
                    BusinessPageProviderMetadata -> put(base.put("kind", "page_local"))
                    is ExtensionHubService -> put(base.put("kind", "extension_hub"))
                    else -> throw PluginInstallException(
                        "WORKER_PROVIDER_NOT_PROXYABLE",
                        "Provider ${record.id} has no structured cross-process contract: ${payload?.let { it::class.java.name } ?: "null"}"
                    )
                }
            }
    }

    private fun extensionDescriptors(pluginId: String): JSONArray = JSONArray().apply {
        contributions.listByOwner(pluginId)
            .filter { it.kind == PluginContributionKind.EXTENSION }
            .sortedWith(compareBy({ it.extensionPoint }, { it.id }))
            .forEach { record ->
                when (val payload = record.payload) {
                    is PluginHomeTileSpec -> put(JSONObject()
                        .put("kind", "home_tile").put("point", record.extensionPoint)
                        .put("id", record.id).put("title", payload.title)
                        .put("description", payload.description).put("screen_id", payload.screenId))
                    is PluginScreenSpec -> put(JSONObject()
                        .put("kind", "screen").put("point", record.extensionPoint)
                        .put("id", record.id).put("title", payload.title)
                        .put("description", payload.description ?: JSONObject.NULL)
                        .put("schema_id", payload.schemaId).put("document_json", payload.documentJson))
                    is PluginThemeSpec -> put(JSONObject()
                        .put("kind", "theme").put("point", record.extensionPoint)
                        .put("id", record.id).put("mode", payload.mode.name)
                        .put("pure_black", payload.pureBlack)
                        .put("colors", JSONObject(payload.colors))
                        .put("background_gradient", JSONArray(payload.backgroundGradient)))
                    is PluginLocalModelLoaderSpec -> throw PluginInstallException(
                        "WORKER_EXTENSION_NOT_PROXYABLE",
                        "Local model loader requires a structured cross-process AIService contract"
                    )
                }
            }
    }

    private fun registerSurfaces() {
        surfacePolicy.register(HostSurfaceDefinition(
            PluginSurfaceIds.PUBLISH_CAPABILITY, "Plugin Capability Bus", "worker capability publication",
            HostSurfaceKind.PLUGIN_CAPABILITY_BUS
        ))
        surfacePolicy.register(HostSurfaceDefinition(
            PluginSurfaceIds.PUBLISH_SERVICE, "Plugin Service Bus", "worker service access",
            HostSurfaceKind.PLUGIN_SERVICE_BUS
        ))
        surfacePolicy.register(HostSurfaceDefinition(
            PluginSurfaceIds.PUBLISH_PROVIDER, "Plugin Provider Bus", "worker provider publication",
            HostSurfaceKind.PLUGIN_PROVIDER_BUS
        ))
        surfacePolicy.register(HostSurfaceDefinition(
            PluginSurfaceIds.HOST_NOTIFICATION, "Notification Host", "worker notification proxy",
            HostSurfaceKind.HOST_PROVIDER, requiredScope = "host.notification@1"
        ))
        listOf(
            PluginExtensionPoints.UI_HOME_TILE to 1,
            PluginExtensionPoints.UI_SCREEN to 2,
            PluginExtensionPoints.UI_THEME to 1,
            PluginExtensionPoints.LOCAL_MODEL_LOADER to 1
        ).forEach { (point, _) ->
            surfacePolicy.register(HostSurfaceDefinition(
                PluginSurfaceIds.extension(point), point, "worker extension point",
                HostSurfaceKind.EXTENSION_POINT
            ))
        }
    }

    private fun registerExtensionPoints() {
        extensionPoints.register(ExtensionPointDefinition(
            PluginExtensionPoints.UI_HOME_TILE, 1
        ) { record ->
            check(record.payload is PluginHomeTileSpec)
            AutoCloseable { }
        })
        extensionPoints.register(ExtensionPointDefinition(
            PluginExtensionPoints.UI_SCREEN, 2
        ) { record ->
            check(record.payload is PluginScreenSpec)
            AutoCloseable { }
        })
        extensionPoints.register(ExtensionPointDefinition(
            PluginExtensionPoints.UI_THEME, 1
        ) { record ->
            check(record.payload is PluginThemeSpec)
            AutoCloseable { }
        })
        extensionPoints.register(ExtensionPointDefinition(
            PluginExtensionPoints.LOCAL_MODEL_LOADER, 1
        ) { _ ->
            throw PluginInstallException(
                "WORKER_EXTENSION_NOT_PROXYABLE",
                "Local model loader cannot execute inside Resident Core"
            )
        })
    }
}
