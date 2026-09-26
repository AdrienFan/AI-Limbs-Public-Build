package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsExecutionAuthorization
import com.ai.assistance.operit.core.tools.system.resident.ResidentRuntimeLease
import com.ai.assistance.operit.core.tools.system.resident.ResidentBusinessTakeoverFence
import com.ai.assistance.operit.plugins.system.KernelPluginPlatformControlV1
import com.ai.assistance.operit.plugins.system.KernelSystemHostGatewayV1
import com.ai.assistance.operit.plugins.system.KernelSystemPluginDelegatedCapabilityInvokerV2
import com.ai.assistance.operit.plugins.system.KernelSystemPluginProviderDirectoryV2
import com.ai.assistance.operit.plugins.system.KernelSystemPluginHostV2
import com.ai.assistance.operit.plugins.system.KernelSystemPluginChildExtensionControlV2
import com.ai.assistance.operit.plugins.system.KernelSystemPluginServicePublisherV2
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemPluginProtocolV1
import com.ai.assistance.operit.plugins.center.isolation.ProviderContributionTransportCodec
import com.ai.assistance.operit.plugins.center.isolation.RemoteAndroidInProcessPluginRuntimeAdapter
import com.ai.assistance.operit.plugins.center.isolation.RemoteChildExtensionRuntimeOwner
import com.ai.assistance.operit.plugins.center.isolation.RemotePageProviderMetadata
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable host-side plugin platform kernel. It owns plugin runtime, policy, storage and registries.
 * Plugin Center UI is intentionally not part of this object; system plugins consume versioned host contracts.
 */
internal object PluginPlatformKernel {
    private enum class FoundationalRuntimePhase {
        STOPPED,
        STARTING,
        READY,
        FAILED
    }

    private const val TAG = "PluginPlatformKernel"
    private val lifecycleLock = Any()
    private val runtimeLifecycleMutex = Mutex()
    // Keep ownership for the process lifetime: initialized registries survive shutdown().
    private var runtimeOwnerLease: ResidentRuntimeLease? = null

    @Volatile private var initialized = false
    @Volatile private var started = false
    @Volatile private var lifecyclePhase = "uninitialized"
    @Volatile private var lifecycleError: String? = null
    @Volatile private var businessRuntimeRestored = false
    @Volatile private var foundationalRuntimePhase = FoundationalRuntimePhase.STOPPED
    @Volatile private var foundationalRuntimeError: String? = null
    @Volatile private var childRuntimeStarted = false
    @Volatile private var residentBridgeIngressPrepared = false
    @Volatile private var residentBridgePluginMounted = false
    @Volatile private var residentBridgeRuntimeReadiness: JSONObject? = null
    @Volatile private var residentPluginServicesPrepared = false
    @Volatile private var residentSubsystemsReady = false
    @Volatile private var residentUbuntuControlReady = false
    @Volatile private var residentUbuntuConfigured = false
    private var residentPluginRuntimeReport: org.json.JSONObject? = null
    private var runtimeRole: PluginRuntimeRole = PluginRuntimeRole.LEGACY_HOST

    internal fun lifecycleSnapshot(): org.json.JSONObject {
        if (!initialized) {
            PluginHostUiProxyRuntimeHolder.currentOrNull()?.let { proxy ->
                return proxy.snapshot()
                    .put("phase", if (proxy.attachment.attachedToLiveCore) "ui_proxy_attached" else "ui_proxy_waiting")
                    .put("initialized", false)
                    .put("started", false)
                    .put("pid", android.os.Process.myPid())
                    .put("uid", android.os.Process.myUid())
                    .put("last_error", org.json.JSONObject.NULL)
            }
        }
        val foundationalRuntime = foundationalRuntimeSnapshotJson()
        return org.json.JSONObject()
            .put("phase", lifecyclePhase)
            .put("runtime_role", runtimeRole.name.lowercase())
            .put("initialized", initialized)
            .put("started", started)
            .put("pid", android.os.Process.myPid())
            .put("uid", android.os.Process.myUid())
            .put("owner_lease_held", runtimeOwnerLease != null)
            .put("business_runtime_restored", businessRuntimeRestored)
            .put("foundational_runtime", foundationalRuntime)
            .put("foundational_runtime_started", foundationalRuntime.getBoolean("started"))
            .put("foundational_runtime_ready", foundationalRuntime.getBoolean("ready"))
            .put("foundational_runtime_stopped", foundationalRuntime.getBoolean("stopped"))
            .put("child_runtime_started", childRuntimeStarted)
            .put("resident_bridge_ingress_prepared", residentBridgeIngressPrepared)
            .put("resident_bridge_plugin_mounted", residentBridgePluginMounted)
            .put("resident_bridge_readiness", residentBridgeRuntimeReadiness ?: JSONObject.NULL)
            .put("resident_plugin_services_prepared", residentPluginServicesPrepared)
            .put("resident_subsystems_ready", residentSubsystemsReady)
            .put("resident_ubuntu_configured", residentUbuntuConfigured)
            .put("resident_ubuntu_control_ready", residentUbuntuControlReady)
            .put("resident_plugin_runtime", residentPluginRuntimeReport ?: org.json.JSONObject.NULL)
            .put("active_capabilities", if (initialized) org.json.JSONArray(capabilityRegistryInstance.activeIds().toList()) else org.json.JSONArray())
            .put("last_error", lifecycleError ?: org.json.JSONObject.NULL)
    }

    private lateinit var appContextInstance: Context
    private lateinit var managerInstance: PluginManager
    private lateinit var runtimeAdaptersInstance: PluginRuntimeAdapterRegistry
    private lateinit var contributionsInstance: PluginContributionRegistry
    private lateinit var extensionPointsInstance: ExtensionPointRegistry
    private lateinit var extensionRouterInstance: ExtensionRouter
    private lateinit var localModelLoadersInstance: LocalModelLoaderRegistry
    private lateinit var uiRegistryInstance: PluginUiRegistry
    private lateinit var systemUiRegistryInstance: SystemPluginUiRegistry
    private lateinit var dynamicNavigationRegistryInstance: DynamicNavigationSurfaceRegistry
    private lateinit var pagePresentationRegistryInstance: PluginPagePresentationRegistry
    private lateinit var capabilityRegistryInstance: PluginHostCapabilityRegistry
    private lateinit var surfacePolicyInstance: HostSurfacePolicy
    private lateinit var adminSecurityInstance: AdminSecurityManager
    private lateinit var usageStoreInstance: PluginUsageStore
    private lateinit var inactivityPolicyInstance: PluginInactivityPolicyStore
    private lateinit var backupPolicyInstance: PluginBackupPolicyStore
    private lateinit var notificationHostInstance: PluginNotificationHost
    private lateinit var officialIdentitiesInstance: OfficialPluginIdentityRegistry
    private lateinit var childExtensionRuntimeInstance: ChildExtensionRuntimeOwner
    private lateinit var systemPluginControllerInstance: com.ai.assistance.operit.plugins.system.SystemPluginController
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var inactivityMonitorJob: Job? = null

    val isInitialized: Boolean get() = initialized
    val isStarted: Boolean get() = started
    internal val foregroundNotification
        get() = requireInitialized().let { notificationHostInstance.foregroundState }
    val hasForegroundNotification: Boolean
        get() =
            initialized &&
                notificationHostInstance.hasForegroundResponsibility

    internal val manager: PluginManager
        get() = requireInitialized().let { managerInstance }
    internal val runtimeAdapters: PluginRuntimeAdapterRegistry
        get() = requireInitialized().let { runtimeAdaptersInstance }
    internal val contributions: PluginContributionRegistry
        get() = requireInitialized().let { contributionsInstance }
    internal val extensionPoints: ExtensionPointRegistry
        get() = requireInitialized().let { extensionPointsInstance }
    internal val extensionRouter: ExtensionRouter
        get() = requireInitialized().let { extensionRouterInstance }
    internal val localModelLoaders: LocalModelLoaderRegistry
        get() = requireInitialized().let { localModelLoadersInstance }
    internal val uiRegistry: PluginUiRegistry
        get() = if (initialized) uiRegistryInstance else PluginHostUiProxyRuntimeHolder.requireRuntime().uiRegistry
    internal val systemUiRegistry: SystemPluginUiRegistry
        get() = if (initialized) systemUiRegistryInstance else PluginHostUiProxyRuntimeHolder.requireRuntime().systemUiRegistry
    internal val dynamicNavigationRegistry: DynamicNavigationSurfaceRegistry
        get() = if (initialized) dynamicNavigationRegistryInstance else PluginHostUiProxyRuntimeHolder.requireRuntime().dynamicNavigationRegistry
    internal val pagePresentationRegistry: PluginPagePresentationRegistry
        get() = if (initialized) pagePresentationRegistryInstance else PluginHostUiProxyRuntimeHolder.requireRuntime().pagePresentationRegistry
    internal val systemPlugins: com.ai.assistance.operit.plugins.system.SystemPluginController
        get() = requireInitialized().let { systemPluginControllerInstance }
    internal val capabilities: PluginHostCapabilityRegistry
        get() = requireInitialized().let { capabilityRegistryInstance }
    val hostSurfacePolicy: HostSurfacePolicy
        get() = requireInitialized().let { surfacePolicyInstance }
    val adminSecurity: AdminSecurityManager
        get() = requireInitialized().let { adminSecurityInstance }
    internal val officialIdentities: OfficialPluginIdentityRegistry
        get() = requireInitialized().let { officialIdentitiesInstance }

    /**
     * Read-only Host presentation provider discovery.
     *
     * BUSINESS Core contributions are used in legacy in-process mode; when Resident owns business,
     * the Host reads its local presentation directory maintained by the UI proxy.
     */
    internal fun presentationProvidersSnapshot(): List<com.ai.limbs.plugin.runtime.InProcessProviderBinding> {
        if (!initialized) {
            return PluginHostUiProxyRuntimeHolder.requireRuntime()
                .presentationProviders()
                .map { binding ->
                    com.ai.limbs.plugin.runtime.InProcessProviderBinding(
                        ownerPluginId = binding.ownerPluginId,
                        id = binding.id,
                        metadata = binding.metadata.toMap(),
                        payload = binding.payload
                    )
                }
        }
        return contributionsInstance.listAll()
            .filter { it.kind == PluginContributionKind.PROVIDER }
            .map { record ->
                com.ai.limbs.plugin.runtime.InProcessProviderBinding(
                    ownerPluginId = record.ownerPluginId,
                    id = record.id,
                    metadata = record.metadata.toMap(),
                    payload = record.payload
                )
            }
            .sortedBy { it.id }
    }

    internal fun matchesBusinessChatModeConfig(
        config: com.ai.assistance.operit.data.model.ModelConfigData
    ): Boolean {
        if (!initialized) return false
        val configId = config.id.trim()
        val providerTypeId = config.apiProviderTypeId.trim()
        return contributionsInstance.listAll()
            .asSequence()
            .filter { it.kind == PluginContributionKind.PROVIDER }
            .filter { it.metadata["kind"] == PluginChatModeRuntime.BUSINESS_KIND }
            .any { record ->
                val declaredConfigId = record.metadata["config_id"].orEmpty().trim()
                val declaredProviderTypeId = record.metadata["provider_type_id"].orEmpty().trim()
                (declaredConfigId.isNotEmpty() && declaredConfigId == configId) ||
                    (declaredProviderTypeId.isNotEmpty() &&
                        declaredProviderTypeId.equals(providerTypeId, ignoreCase = true))
            }
    }

    /**

     * Runtime hand-off for an already admitted system plugin. Trust/signature admission must happen
     * before this internal boundary is called. Only the plugin_center role receives this control plane.
     */
    internal fun createAdmittedSystemHost(
        ownerPluginId: String,
        admittedRole: String
    ): SystemPluginHostV2 {
        requireInitialized()
        val gateway = KernelSystemHostGatewayV1(
            ownerPluginId = ownerPluginId,
            admittedRole = admittedRole,
            capabilityRegistry = capabilityRegistryInstance,
            surfacePolicy = surfacePolicyInstance
        )
        val control = KernelPluginPlatformControlV1(
            admittedRole = admittedRole,
            manager = managerInstance,
            capabilityRegistry = capabilityRegistryInstance,
            surfacePolicy = surfacePolicyInstance
        )
        val systemUi = com.ai.assistance.operit.plugins.system.KernelSystemUiHostV1(
            ownerPluginId = ownerPluginId,
            admittedRole = admittedRole,
            registry = systemUiRegistryInstance,
            runtimeRole = runtimeRole
        )
        val services = KernelSystemPluginServicePublisherV2(
            ownerPluginId = ownerPluginId,
            admittedRole = admittedRole,
            contributions = contributionsInstance
        )
        val delegatedCapabilities = KernelSystemPluginDelegatedCapabilityInvokerV2(
            admittedRole = admittedRole,
            manager = managerInstance,
            capabilityRegistry = capabilityRegistryInstance
        )
        // Read-only provider discovery is needed by Plugin Center's generic UI renderer.  It does
        // not expose provider registration, so moving UI semantics out of Host does not transfer
        // provider ownership to the system plugin.
        val providers = KernelSystemPluginProviderDirectoryV2(
            admittedRole = admittedRole,
            contributions = contributionsInstance
        )
        val childExtensions = KernelSystemPluginChildExtensionControlV2(
            admittedRole = admittedRole,
            runtime = childExtensionRuntimeInstance.bound(
                "kernel.child_runtime.controller",
                setOf(ChildRuntimeAuthorityRoles.RUNTIME_CONTROLLER),
                emptySet()
            ),
            runtimeOwner = childExtensionRuntimeInstance
        )
        val pluginAdmin = com.ai.assistance.operit.plugins.system.KernelPluginAdminJsonServiceV1(
            context = appContextInstance,
            manager = managerInstance,
            surfacePolicy = surfacePolicyInstance,
            inactivityPolicy = inactivityPolicyInstance,
            backupPolicy = backupPolicyInstance,
            identityRegistry = officialIdentitiesInstance
        )
        val adminSecurity = com.ai.assistance.operit.plugins.system.KernelAdminSecurityJsonServiceV1(adminSecurityInstance)
        val selfMaintenance = com.ai.assistance.operit.plugins.system.KernelSelfMaintenanceJsonServiceV1(systemPluginControllerInstance)
        val navigation = com.ai.assistance.operit.plugins.system.KernelDynamicNavigationJsonServiceV1(
            registry = dynamicNavigationRegistryInstance,
            uiRegistry = uiRegistryInstance,
            adminSecurity = adminSecurityInstance
        )
        return KernelSystemPluginHostV2(
            SystemPluginProtocolV1.HOST_ABI,
            gateway,
            control,
            pluginAdmin,
            adminSecurity,
            selfMaintenance,
            navigation,
            systemUi,
            services,
            delegatedCapabilities,
            providers,
            childExtensions
        )
    }


    /** Neutral JSON-only presentation state exported from BUSINESS Core to Host UI_PROXY. */
    internal suspend fun residentUiProxySnapshot(): JSONObject {
        requireInitialized()
        check(runtimeRole == PluginRuntimeRole.BUSINESS && residentPluginServicesPrepared) {
            "Resident UI snapshot requires prepared BUSINESS plugin services"
        }
        val tiles = JSONArray().apply {
            uiRegistryInstance.homeTiles.value.forEach { tile ->
                put(JSONObject().put("owner_plugin_id", tile.ownerPluginId).put("id", tile.id)
                    .put("title", tile.title).put("description", tile.description).put("screen_id", tile.screenId))
            }
        }
        val screens = JSONArray().apply {
            uiRegistryInstance.activeScreens.value.forEach { screen ->
                put(JSONObject().put("owner_plugin_id", screen.ownerPluginId).put("id", screen.id)
                    .put("title", screen.title).put("description", screen.description ?: JSONObject.NULL)
                    .put("schema_id", screen.schemaId).put("document_json", screen.documentJson))
            }
        }
        val theme = uiRegistryInstance.activeTheme.value?.let { value ->
            JSONObject().put("owner_plugin_id", value.ownerPluginId).put("id", value.id)
                .put("mode", value.mode.name.lowercase()).put("pure_black", value.pureBlack)
                .put("colors", JSONObject(value.colors)).put("background_gradient", JSONArray(value.backgroundGradient))
        }
        val presentations = JSONArray().apply {
            pagePresentationRegistryInstance.requests.value.values.sortedBy { it.screenId }.forEach { request ->
                put(JSONObject().put("owner_plugin_id", request.ownerPluginId).put("screen_id", request.screenId)
                    .put("mode", request.mode.name.lowercase()))
            }
        }
        val providers = JSONArray().apply {
            contributionsInstance.listAll().filter { it.kind == PluginContributionKind.PROVIDER }.sortedBy { it.id }.forEach { record ->
                when (record.payload) {
                    is com.ai.limbs.plugin.runtime.InProcessUiStateProvider,
                    BusinessPageProviderMetadata,
                    RemotePageProviderMetadata -> put(ProviderContributionTransportCodec.encode(record))
                    is com.ai.limbs.plugin.runtime.InProcessPageProvider -> Unit // View/Context ABI: never exported.
                    else -> Unit
                }
            }
        }
        val pluginPresentations = JSONArray().apply {
            managerInstance.snapshots()
                .filter { snapshot ->
                    val state = snapshot.persistentState
                    state?.enabled == true && state.lastState == PluginLifecycleState.ACTIVE &&
                        snapshot.mountedVersion == state.activeVersion && snapshot.activeManifest?.runtime?.kind == "android_inprocess"
                }
                .sortedBy { it.pluginId }
                .forEach { snapshot ->
                    val manifest = snapshot.activeManifest ?: return@forEach
                    val config = manifest.runtime.configJson?.let(::JSONObject) ?: JSONObject()
                    val presentationEntry = config.optString("presentation_entry_class").trim()
                    val runtimeEntry = manifest.runtime.entry?.trim().orEmpty()
                    if (presentationEntry.isNotEmpty() && runtimeEntry.isNotEmpty()) {
                        put(JSONObject()
                            .put("plugin_id", snapshot.pluginId)
                            .put("version", snapshot.mountedVersion)
                            .put("runtime_entry", runtimeEntry)
                            .put("presentation_entry_class", presentationEntry))
                    }
                }
        }
        val childSnapshots = JSONArray().apply { childExtensionRuntimeInstance.loggingSnapshots().forEach { put(childSnapshotJson(it)) } }
        val childBackups = JSONArray().apply { childExtensionRuntimeInstance.loggingBackupSnapshots().forEach { put(childBackupJson(it)) } }
        val childUi = JSONArray().apply {
            childExtensionRuntimeInstance.loggingUiContributions().forEach { contribution ->
                put(JSONObject().put("extension_id", contribution.extensionId)
                    .put("parent_plugin_id", contribution.target.parentPluginId).put("point", contribution.target.point)
                    .put("api_version", contribution.target.apiVersion).put("screen_id", contribution.screenId)
                    .put("component_id", contribution.componentId).put("slot_id", contribution.slotId)
                    .put("contribution_id", contribution.contributionId)
                    .put("document_json", contribution.provider.documentJson.value ?: JSONObject.NULL))
            }
        }
        val navigationSurfaces = JSONArray().apply {
            dynamicNavigationRegistryInstance.surfaces.value.forEach { surface ->
                put(JSONObject().put("id", surface.id).put("title", surface.title).put("icon_key", surface.iconKey)
                    .put("order", surface.order).put("created_at", surface.createdAt))
            }
        }
        val navigationBindings = JSONArray().apply {
            dynamicNavigationRegistryInstance.bindings.value.forEach { binding ->
                put(JSONObject().put("surface_id", binding.surfaceId).put("owner_plugin_id", binding.ownerPluginId)
                    .put("tile_id", binding.tileId).put("screen_id", binding.screenId))
            }
        }
        val notification = notificationHostInstance.foregroundState.value?.let { snapshot ->
            val state = snapshot.state
            JSONObject()
                .put("binding_id", snapshot.bindingId)
                .put("owner_plugin_id", snapshot.ownerPluginId)
                .put("state", JSONObject()
                    .put("title", state.title)
                    .put("summary", state.summary)
                    .put("status_lines", JSONArray(state.statusLines))
                    .put("actions", JSONArray().apply {
                        state.actions.forEach { action ->
                            put(JSONObject()
                                .put("id", action.id)
                                .put("label", action.label)
                                .put("priority", action.priority)
                                .put("enabled", action.enabled))
                        }
                    }))
        }
        return JSONObject().put("schema", 1).put("runtime_role", "business")
            .put("business_runtime_restored", businessRuntimeRestored).put("tiles", tiles).put("screens", screens)
            .put("theme", theme ?: JSONObject.NULL).put("presentations", presentations).put("providers", providers)
            .put("plugin_presentations", pluginPresentations)
            .put("child_presentations", childExtensionRuntimeInstance.residentPresentationDescriptors())
            .put("children", childSnapshots).put("child_backups", childBackups).put("child_ui_contributions", childUi)
            .put("navigation_surfaces", navigationSurfaces).put("navigation_bindings", navigationBindings)
            .put("developer_mode", surfacePolicyInstance.developerMode)
            .put("developer_discovery_enabled", surfacePolicyInstance.developerDiscoveryEnabled)
            .put("host_primitives", hostPrimitiveSnapshotJson())
            .put("notification", notification ?: JSONObject.NULL)
    }

    internal suspend fun dispatchResidentUiProxyCommand(request: JSONObject): JSONObject {
        requireInitialized()
        check(runtimeRole == PluginRuntimeRole.BUSINESS && residentPluginServicesPrepared) {
            "Resident UI command requires prepared BUSINESS plugin services"
        }
        return when (val command = request.getString("command")) {
            "notification_action" -> {
                val bindingId = request.getString("binding_id").trim()
                val actionId = request.getString("action_id").trim()
                check(bindingId.isNotEmpty() && actionId.isNotEmpty()) { "Notification action IDs are required" }
                JSONObject().put("accepted", notificationHostInstance.dispatch(bindingId, actionId))
            }
            "set_active_screen" -> {
                val screenId = request.optString("screen_id").trim().ifBlank { null }
                if (screenId != null) {
                    check(uiRegistryInstance.screen(screenId) != null) { "UI proxy reported an unknown active screen: $screenId" }
                }
                pagePresentationRegistryInstance.setActiveScreen(screenId)
                JSONObject().put("ok", true)
            }
            "set_presentation_mode" -> {
                val owner = request.getString("owner_plugin_id").trim()
                val screenId = request.getString("screen_id").trim()
                val screen = uiRegistryInstance.screen(screenId)
                    ?: throw PluginInstallException("UI_SCREEN_UNKNOWN", "Core screen is not active: $screenId")
                check(screen.ownerPluginId == owner) { "UI presentation owner mismatch" }
                check(pagePresentationRegistryInstance.isActiveScreen(screenId)) { "UI presentation screen is not foreground" }
                pagePresentationRegistryInstance.set(
                    owner,
                    screenId,
                    PluginPagePresentationMode.parse(request.getString("mode"))
                )
                JSONObject().put("ok", true)
            }
            "invoke_ui_capability" -> {
                val owner = request.getString("owner_plugin_id").trim()
                val screenId = request.getString("screen_id").trim()
                val capabilityId = request.getString("capability_id").trim().lowercase()
                val screen = uiRegistryInstance.screen(screenId)
                    ?: throw PluginInstallException("UI_SCREEN_UNKNOWN", "Core screen is not active: $screenId")
                check(screen.ownerPluginId == owner) { "UI screen owner mismatch" }
                AiLimbsExecutionAuthorization.withExplicitUiAction(owner, screenId, capabilityId) {
                    capabilityRegistryInstance.requireOwnedCapability(owner, capabilityId)
                    val authorization = managerInstance.activeAuthorization(owner)
                    capabilityRegistryInstance.invokeDelegated(authorization.pluginId, authorization.grantedScopes, capabilityId,
                        request.optJSONObject("parameters") ?: JSONObject())
                }
            }
            "ui_provider_event" -> {
                val id = request.getString("provider_id").trim()
                val owner = request.getString("owner_plugin_id").trim()
                val record = contributionsInstance.find(PluginContributionKind.PROVIDER, id)
                    ?: throw PluginInstallException("UI_PROVIDER_UNKNOWN", "UI state provider is not active: $id")
                check(record.ownerPluginId == owner) { "UI provider owner mismatch" }
                val provider = record.payload as? com.ai.limbs.plugin.runtime.InProcessUiStateProvider
                    ?: throw PluginInstallException("UI_PROVIDER_NOT_PROXYABLE", "Provider is not a JSON UI state channel: $id")
                JSONObject().put("result_json", provider.perform(request.getString("event_id"), request.optString("payload_json", "{}")))
            }
            "child_ui_event" -> {
                val extensionId = request.getString("extension_id").trim()
                val contributionId = request.getString("contribution_id").trim()
                val contribution = childExtensionRuntimeInstance.loggingUiContributions().firstOrNull {
                    it.extensionId == extensionId && it.contributionId == contributionId
                } ?: throw PluginInstallException("CHILD_UI_CONTRIBUTION_UNKNOWN", "Child UI contribution is not active")
                JSONObject().put("result_json", contribution.provider.perform(request.getString("event_id"), request.optString("payload_json", "{}")))
            }
            "system_json" -> {
                val host = createAdmittedSystemHost(com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_PLUGIN_ID, SystemPluginProtocolV1.ROLE_PLUGIN_CENTER)
                val service = when (request.getString("service")) {
                    "plugin_admin" -> host.pluginAdmin
                    "admin_security" -> host.adminSecurity
                    "navigation" -> host.navigation
                    else -> throw PluginInstallException("UI_PROXY_SYSTEM_SERVICE_FORBIDDEN", "Unsupported Core system JSON service")
                }
                service.call(request.getString("operation"), request.optJSONObject("parameters") ?: JSONObject())
            }
            "delegated_capability" -> {
                val authorization = managerInstance.activeAuthorization(request.getString("plugin_id").trim())
                capabilityRegistryInstance.invokeDelegated(authorization.pluginId, authorization.grantedScopes,
                    request.getString("capability_id"), request.optJSONObject("parameters") ?: JSONObject())
            }
            "presentation_host_capability" -> {
                val owner = request.getString("owner_plugin_id").trim()
                val authorization = managerInstance.activeAuthorization(owner)
                val invoker = capabilityRegistryInstance.create(authorization.pluginId, authorization.grantedScopes)
                invoker.invoke(
                    request.getString("capability_id").trim().lowercase(),
                    request.optJSONObject("parameters") ?: JSONObject()
                )
            }
            "presentation_plugin_capability" -> {
                val owner = request.getString("owner_plugin_id").trim()
                val capabilityId = request.getString("capability_id").trim().lowercase()
                // A signed presentation entry invoking a capability owned by the same active plugin
                // is an explicit user-UI action, not a new AI/Bridge delegation. Ownership is
                // re-checked by invokeOwnedUiDirect, while Host scopes remain enforced downstream.
                managerInstance.activeAuthorization(owner)
                capabilityRegistryInstance.invokeOwnedUiDirect(
                    owner,
                    capabilityId,
                    request.optJSONObject("parameters") ?: JSONObject()
                )
            }

            "presentation_child_capability" -> {
                val extensionId = request.getString("extension_id").trim()
                val capabilityId = request.getString("capability_id").trim().lowercase()
                val child = childExtensionRuntimeInstance.loggingSnapshots().firstOrNull {
                    it.extensionId == extensionId && it.enabled && it.lifecycle == com.ai.limbs.plugin.runtime.ChildExtensionLifecycle.ACTIVE
                } ?: throw PluginInstallException(
                    "CHILD_PRESENTATION_OWNER_INACTIVE",
                    "Child presentation owner is not ACTIVE: $extensionId"
                )
                val expectedParent = request.optString("parent_plugin_id").trim()
                if (expectedParent.isNotEmpty()) {
                    check(child.target.parentPluginId == expectedParent) { "Child presentation parent mismatch" }
                }
                capabilityRegistryInstance.invokeOwnedUiDirect(
                    extensionId,
                    capabilityId,
                    request.optJSONObject("parameters") ?: JSONObject()
                )
            }
            "presentation_child_command" -> {
                val extensionId = request.getString("extension_id").trim()
                val child = childExtensionRuntimeInstance.loggingSnapshots().firstOrNull {
                    it.extensionId == extensionId && it.enabled && it.lifecycle == com.ai.limbs.plugin.runtime.ChildExtensionLifecycle.ACTIVE
                } ?: throw PluginInstallException(
                    "CHILD_PRESENTATION_OWNER_INACTIVE",
                    "Child presentation owner is not ACTIVE: $extensionId"
                )
                val expectedParent = request.optString("parent_plugin_id").trim()
                if (expectedParent.isNotEmpty()) {
                    check(child.target.parentPluginId == expectedParent) { "Child presentation parent mismatch" }
                }
                childExtensionRuntimeInstance.invokePresentationCommand(
                    extensionId = extensionId,
                    command = request.getString("presentation_command"),
                    parameters = request.optJSONObject("parameters") ?: JSONObject()
                )
            }
            "plugin_platform" -> {
                when (request.getString("operation")) {
                    "set_developer_mode" -> surfacePolicyInstance.setDeveloperMode(request.getBoolean("enabled"))
                    "set_developer_discovery" -> surfacePolicyInstance.setDeveloperDiscoveryEnabled(request.getBoolean("enabled"))
                    "set_host_primitive_allowed" -> {
                        val id = request.getString("primitive_id")
                        val primitive = AiLimbsHostPrimitiveCatalog.find(id)
                            ?: throw PluginInstallException("HOST_PRIMITIVE_UNKNOWN", "Unknown Host Primitive: $id")
                        check(primitive.requestableScope && primitive.exposure == HostPrimitiveExposure.BOUND) { "Host Primitive is not user-toggleable: $id" }
                        surfacePolicyInstance.setScopeAllowed(primitive.id, request.getBoolean("allowed"))
                        managerInstance.reconcileHostSurfacePolicy()
                    }
                    else -> throw PluginInstallException("UI_PROXY_PLATFORM_OPERATION_UNKNOWN", "Unsupported plugin platform operation")
                }
                JSONObject().put("ok", true)
            }
            "host_primitive" -> {
                val owner = com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_PLUGIN_ID
                val id = request.getString("id")
                val params = request.optJSONObject("parameters") ?: JSONObject()
                if (request.has("host_operation") && !request.isNull("host_operation"))
                    capabilityRegistryInstance.invokeSystemHost(owner, id, request.getString("host_operation"), params)
                else capabilityRegistryInstance.invokeSystemHost(owner, id, params)
            }
            "child_control" -> dispatchResidentChildControl(request)
            else -> throw PluginInstallException("UI_PROXY_COMMAND_UNKNOWN", "Unsupported Resident UI command: $command")
        }
    }

    private suspend fun dispatchResidentChildControl(request: JSONObject): JSONObject {
        val id = request.optString("extension_id").trim()
        val controller = childExtensionRuntimeInstance.bound(
            "kernel.child_runtime.controller",
            setOf(ChildRuntimeAuthorityRoles.RUNTIME_CONTROLLER),
            emptySet()
        )
        return when (request.getString("operation")) {
            "uninstall" -> JSONObject().put("removed", controller.uninstall(id))
            "set_enabled" -> childSnapshotJson(controller.setEnabled(id, request.getBoolean("enabled")))
            "backup" -> childBackupJson(controller.backup(id))
            "restore_backup" -> childSnapshotJson(controller.restoreBackup(id))
            "delete_backup" -> JSONObject().put("deleted", controller.deleteBackup(id))
            "activate_version" -> childSnapshotJson(controller.activateVersion(id, request.getString("version")))
            "immediate_rollback" -> childSnapshotJson(controller.immediateRollback(id))
            "delete_version" -> JSONObject().put("deleted", controller.deleteVersion(id, request.getString("version")))
            "set_version_retention" -> { controller.setVersionRetention(id, request.getInt("limit")); JSONObject().put("ok", true) }
            "set_auto_backup_policy" -> {
                controller.setAutoBackupPolicy(request.getBoolean("enabled"), request.optLong("high_frequency_use_count", 10L))
                JSONObject().put("ok", true)
            }
            "export_backups" -> {
                val ids = buildList {
                    val array = request.optJSONArray("extension_ids") ?: JSONArray()
                    for (index in 0 until array.length()) add(array.getString(index))
                }
                JSONObject().put("exported", JSONArray(childExtensionRuntimeInstance.exportBackups(ids, request.getString("tree_uri"))))
            }
            else -> throw PluginInstallException("UI_PROXY_CHILD_OPERATION_UNKNOWN", "Unsupported child control operation")
        }
    }

    private fun childSnapshotJson(value: com.ai.limbs.plugin.runtime.ChildExtensionSnapshot): JSONObject = JSONObject()
        .put("extension_id", value.extensionId).put("version", value.version).put("display_name", value.displayName)
        .put("description", value.description ?: JSONObject.NULL).put("parent_plugin_id", value.target.parentPluginId)
        .put("point", value.target.point).put("api_version", value.target.apiVersion).put("lifecycle", value.lifecycle.name.lowercase())
        .put("enabled", value.enabled).put("roles", JSONArray(value.roles.toList().sorted())).put("use_count", value.useCount)
        .put("last_error", value.lastError ?: JSONObject.NULL)

    private fun childBackupJson(value: com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot): JSONObject = JSONObject()
        .put("extension_id", value.extensionId).put("version", value.version).put("display_name", value.displayName)
        .put("description", value.description ?: JSONObject.NULL).put("parent_plugin_id", value.target.parentPluginId)
        .put("point", value.target.point).put("api_version", value.target.apiVersion).put("roles", JSONArray(value.roles.toList().sorted()))
        .put("package_sha256", value.packageSha256).put("backed_up_at", value.backedUpAtEpochMs).put("was_enabled", value.wasEnabled)
        .put("installed", value.installed).put("installed_version", value.installedVersion ?: JSONObject.NULL)

    internal suspend fun describeWorkerService(
        pluginId: String,
        version: String,
        serviceId: String,
        requestedMinApi: Int?
    ): JSONObject {
        requireInitialized()
        val access = managerInstance.workerServiceAuthorization(pluginId, version, serviceId, requestedMinApi)
        val record = contributionsInstance.find(PluginContributionKind.SERVICE, access.serviceId)
            ?: return JSONObject().put("available", false).put("service_id", access.serviceId)
        val actualApi = record.apiVersion ?: 0
        if (actualApi < access.requiredApi) {
            return JSONObject().put("available", false).put("service_id", access.serviceId)
                .put("actual_api", actualApi).put("required_api", access.requiredApi)
        }
        requireWorkerServiceCallable(access.serviceId, record.payload)
        return JSONObject().put("available", true).put("service_id", access.serviceId)
            .put("owner_plugin_id", record.ownerPluginId).put("api_version", actualApi)
            .put("metadata", JSONObject(record.metadata))
    }

    internal suspend fun invokeWorkerService(
        pluginId: String,
        version: String,
        serviceId: String,
        requestedMinApi: Int?,
        operation: String,
        parameters: JSONObject
    ): JSONObject {
        requireInitialized()
        val access = managerInstance.workerServiceAuthorization(pluginId, version, serviceId, requestedMinApi)
        val record = contributionsInstance.find(PluginContributionKind.SERVICE, access.serviceId)
            ?: throw PluginInstallException("SERVICE_UNAVAILABLE", "Service is not active: ${access.serviceId}")
        val actualApi = record.apiVersion ?: 0
        if (actualApi < access.requiredApi) {
            throw PluginInstallException("SERVICE_API_TOO_OLD", "Service ${access.serviceId} does not satisfy API ${access.requiredApi}")
        }
        requireWorkerServiceCallable(access.serviceId, record.payload)
        val caller = PluginServiceCaller(access.caller.pluginId, access.caller.roles, access.caller.grantedScopes)
        val copy = JSONObject(parameters.toString())
        return when (val endpoint = record.payload) {
            is CallerAwarePluginServiceEndpoint -> endpoint.invoke(caller, operation, copy)
            is PluginServiceEndpoint -> endpoint.invoke(operation, copy)
            else -> error("unreachable")
        }
    }

    private fun requireWorkerServiceCallable(serviceId: String, payload: Any?) {
        if (payload !is CallerAwarePluginServiceEndpoint && payload !is PluginServiceEndpoint) {
            throw PluginInstallException("SERVICE_NOT_CALLABLE", "Service $serviceId does not expose a controlled endpoint contract")
        }
    }

    private fun hostPrimitiveSnapshotJson(): JSONArray = JSONArray().apply {
        AiLimbsHostPrimitiveCatalog.all.forEach { definition ->
            val allowed = if (definition.requestableScope && definition.exposure == HostPrimitiveExposure.BOUND) surfacePolicyInstance.isScopeAllowed(definition.id) else null
            put(JSONObject().put("number", definition.number).put("id", definition.id).put("title", definition.title)
                .put("description", definition.description).put("boundary", definition.boundary).put("maturity", definition.maturity.name)
                .put("exposure", definition.exposure.name).put("requestable_scope", definition.requestableScope)
                .put("policy_allowed", allowed ?: JSONObject.NULL).put("callable", capabilityRegistryInstance.isHostCallable(definition.id))
                .put("operations", JSONArray(capabilityRegistryInstance.systemHostOperations(definition.id))))
        }
    }

    fun dispatchNotificationAction(bindingId: String, actionId: String): Boolean {
        if (!initialized) return false
        return notificationHostInstance.dispatch(bindingId, actionId)
    }

    fun recordPluginUse(pluginId: String) {
        requireInitialized()
        val count = usageStoreInstance.recordUse(pluginId)
        if (backupPolicyInstance.snapshot().enabled && count == PluginBackupPolicyStore.HIGH_FREQUENCY_USE_COUNT) {
            monitorScope.launch {
                runCatching { managerInstance.reconcileBackupPolicy() }
                    .onFailure { AppLogger.w(TAG, "High-frequency plugin backup check failed", it) }
            }
        }
    }

    fun initialize(
        context: Context,
        secretBroker: PluginSecretBroker = NoApprovedPluginSecretBroker,
        role: PluginRuntimeRole = PluginRuntimeRole.LEGACY_HOST,
        ownerLease: ResidentRuntimeLease? = null
    ) {
        synchronized(lifecycleLock) {
            if (initialized) {
                check(runtimeRole == role) {
                    "Plugin kernel already initialized as $runtimeRole, requested $role"
                }
                check(ownerLease == null || runtimeOwnerLease === ownerLease) {
                    "Plugin kernel owner lease changed after initialization"
                }
                return
            }
            check(role != PluginRuntimeRole.UI_PROXY) {
                "UI_PROXY is a Host-shell role and must not initialize PluginPlatformKernel"
            }
            check(role == PluginRuntimeRole.BUSINESS || ownerLease == null) {
                "Only BUSINESS may adopt a pre-acquired plugin_kernel lease"
            }
            check(role != PluginRuntimeRole.BUSINESS || ownerLease != null) {
                "BUSINESS must acquire plugin_kernel before PluginPlatformKernel initialization"
            }
            runtimeRole = role
            val appContext = context.applicationContext
            if (runtimeOwnerLease == null) {
                if (role == PluginRuntimeRole.LEGACY_HOST) {
                    ResidentBusinessTakeoverFence.assertLegacyHostStartAllowed(appContext)
                }
                runtimeOwnerLease = ownerLease ?: ResidentRuntimeLease.acquire(
                    File(appContext.filesDir, "ai_limbs/runtime_owner"), "plugin_kernel"
                )
            }
            val surfacePolicy = HostSurfacePolicy(appContext)
            val adminSecurity = AdminSecurityManager(appContext)
            val usageStore = PluginUsageStore(appContext)
            val inactivityPolicy = PluginInactivityPolicyStore(appContext)
            val backupPolicy = PluginBackupPolicyStore(appContext)
            val uiRegistry = PluginUiRegistry()
            val systemUiRegistry = SystemPluginUiRegistry(runtimeRole)
            val dynamicNavigationRegistry = DynamicNavigationSurfaceRegistry(appContext)
            val pagePresentationRegistry = PluginPagePresentationRegistry()
            val pluginStore = PluginStore.fromContext(appContext)
            val loggingService = HostLoggingService(appContext, pluginStore)
            val capabilityRegistry = PluginHostCapabilityRegistry(
                appContext,
                surfacePolicy,
                usageStore,
                loggingService,
                role
            )
            val contributions = PluginContributionRegistry()
            surfacePolicy.register(
                HostSurfaceDefinition(
                    id = PluginSurfaceIds.HOST_NOTIFICATION,
                    title = "通知宿主 · host.notification@1",
                    detail = "允许批准的插件发布受宿主管控的通知状态与最多两个快捷动作",
                    requiredScope = PluginNotificationHost.NOTIFICATION_SCOPE,
                    kind = HostSurfaceKind.HOST_PROVIDER,
                    publicContracts = listOf(
                        "InProcessNotificationHost",
                        "InProcessNotificationState",
                        "InProcessNotificationAction"
                    )
                )
            )
            val notificationHost = PluginNotificationHost(appContext, surfacePolicy, runtimeRole)
            val officialIdentities = OfficialPluginIdentityRegistry(appContext)
            val runtimeAdapters = PluginRuntimeAdapterRegistry().apply {
                register(NoopPluginRuntimeAdapter)
                register(DeclarativePluginRuntimeAdapter)
                if (runtimeRole == PluginRuntimeRole.BUSINESS) {
                    register(
                        RemoteAndroidInProcessPluginRuntimeAdapter { pluginId, scopes ->
                            notificationHost.bindingFor(pluginId, scopes)
                        }
                    )
                } else {
                    register(
                        AndroidInProcessPluginRuntimeAdapter(
                            contributions = contributions,
                            notificationBindingProvider = { pluginId, scopes ->
                                notificationHost.bindingFor(pluginId, scopes)
                            },
                            identityRegistry = officialIdentities,
                            childRuntimeProvider = { childExtensionRuntimeInstance }
                        )
                    )

                }
            }
            listOf(
                Triple(PluginExtensionPoints.UI_HOME_TILE, "首页入口", "允许插件向 AI Limbs 首页添加入口"),
                Triple(PluginExtensionPoints.UI_SCREEN, "插件页面", "允许插件提供可打开的界面页面"),
                Triple(PluginExtensionPoints.UI_THEME, "全局主题 / 皮肤", "允许插件实时接管宿主主题与配色"),
                Triple(PluginExtensionPoints.LOCAL_MODEL_LOADER, "本地模型加载器", "允许插件提供新的本地模型加载实现；内嵌 MNN/llama.cpp 保持默认")
            ).forEach { (point, title, detail) ->
                // UI screen v2 is a permanent opaque-document boundary.  Component evolution belongs
                // to Plugin Center's schema, not this extension point.  Home tile/theme stay on v1.
                val pointApi = if (point == PluginExtensionPoints.UI_SCREEN) 2 else 1
                val contracts = when (point) {
                    PluginExtensionPoints.UI_HOME_TILE -> listOf(
                        "PluginRegistrar.registerExtension",
                        "PluginHomeTileSpec"
                    )
                    PluginExtensionPoints.UI_SCREEN -> listOf(
                        "PluginRegistrar.registerExtension",
                        "PluginScreenSpec(schemaId, opaque documentJson)"
                    )
                    PluginExtensionPoints.UI_THEME -> listOf(
                        "PluginRegistrar.registerExtension",
                        "PluginThemeSpec"
                    )
                    PluginExtensionPoints.LOCAL_MODEL_LOADER -> listOf(
                        "PluginRegistrar.registerExtension",
                        "PluginLocalModelLoaderSpec"
                    )
                    else -> emptyList()
                }
                surfacePolicy.register(
                    HostSurfaceDefinition(
                        id = PluginSurfaceIds.extension(point),
                        title = "$title · $point@$pointApi",
                        detail = detail,
                        kind = HostSurfaceKind.EXTENSION_POINT,
                        publicContracts = contracts
                    )
                )
            }
            val localModelLoaders = LocalModelLoaderRegistry().apply {
                registerBuiltIn("built-in:mnn", "MNN", setOf("MNN"))
                registerBuiltIn("built-in:llama_cpp", "llama.cpp", setOf("LLAMA_CPP"))
            }
            val extensionPoints = ExtensionPointRegistry().apply {
                register(
                    ExtensionPointDefinition(
                        point = PluginExtensionPoints.UI_HOME_TILE,
                        apiVersion = 1,
                        binder = { record ->
                            val tile = record.payload as? PluginHomeTileSpec
                                ?: throw PluginInstallException(
                                    "UI_EXTENSION_PAYLOAD_INVALID",
                                    "Home tile payload has the wrong type"
                                )
                            uiRegistry.registerHomeTile(record.ownerPluginId, tile)
                        }
                    )
                )
                register(
                    ExtensionPointDefinition(
                        point = PluginExtensionPoints.UI_SCREEN,
                        apiVersion = 2,
                        binder = { record ->
                            val screen = record.payload as? PluginScreenSpec
                                ?: throw PluginInstallException(
                                    "UI_EXTENSION_PAYLOAD_INVALID",
                                    "Screen payload has the wrong type"
                                )
                            uiRegistry.registerScreen(record.ownerPluginId, screen)
                        }
                    )
                )
                register(
                    ExtensionPointDefinition(
                        point = PluginExtensionPoints.UI_THEME,
                        apiVersion = 1,
                        binder = { record ->
                            val theme = record.payload as? PluginThemeSpec
                                ?: throw PluginInstallException(
                                    "UI_EXTENSION_PAYLOAD_INVALID",
                                    "Theme payload has the wrong type"
                                )
                            uiRegistry.registerTheme(record.ownerPluginId, theme)
                        }
                    )
                )
                register(
                    ExtensionPointDefinition(
                        point = PluginExtensionPoints.LOCAL_MODEL_LOADER,
                        apiVersion = 1,
                        binder = { record ->
                            val loader = record.payload as? PluginLocalModelLoaderSpec
                                ?: throw PluginInstallException(
                                    "LOCAL_MODEL_LOADER_PAYLOAD_INVALID",
                                    "Local model loader payload has the wrong type"
                                )
                            localModelLoaders.registerPlugin(record.ownerPluginId, record.id, loader)
                        }
                    )
                )
            }
            val extensionRouter = ExtensionRouter(extensionPoints, surfacePolicy)
            val pluginContextFactory = PluginContextFactory(
                contributions = contributions,
                eventBusHost = PluginEventBusHost(),
                capabilityInvokerFactory = capabilityRegistry,
                secretBroker = secretBroker,
                surfacePolicy = surfacePolicy
            )
            val childExtensionRuntime: ChildExtensionRuntimeOwner =
                if (runtimeRole == PluginRuntimeRole.BUSINESS) {
                    RemoteChildExtensionRuntimeOwner(
                        appContext,
                        contributions,
                        capabilityRegistry
                    )
                } else {
                    ChildExtensionRuntime(
                        appContext = appContext,
                        runtimeRole = runtimeRole,
                        pluginStore = pluginStore,
                        contributions = contributions,
                        capabilityRegistry = capabilityRegistry
                    )
                }
            loggingService.bindChildSourceProvider(childExtensionRuntime::loggingSnapshots)
            val backupStore = PluginBackupStore(pluginStore)
            val manager = PluginManager(
                appContext = appContext,
                runtimeRole = runtimeRole,
                store = pluginStore,
                trustVerifier = StrictPluginTrustVerifier,
                runtimeAdapters = runtimeAdapters,
                contributions = contributions,
                extensionRouter = extensionRouter,
                capabilityBinder = capabilityRegistry,
                surfacePolicy = surfacePolicy,
                usageStore = usageStore,
                inactivityPolicy = inactivityPolicy,
                backupStore = backupStore,
                backupPolicy = backupPolicy,
                runtimeHost = PluginRuntimeHost(runtimeRole),
                pluginContextFactory = pluginContextFactory,
                identityRegistry = officialIdentities,
                packageVerifier = PluginPackageVerifier(officialIdentities)
            )
            manager.initialize()
            loggingService.bindPluginSourceProvider(manager::snapshots)
            appContextInstance = appContext
            managerInstance = manager
            runtimeAdaptersInstance = runtimeAdapters
            contributionsInstance = contributions
            extensionPointsInstance = extensionPoints
            extensionRouterInstance = extensionRouter
            localModelLoadersInstance = localModelLoaders
            uiRegistryInstance = uiRegistry
            systemUiRegistryInstance = systemUiRegistry
            dynamicNavigationRegistryInstance = dynamicNavigationRegistry
            pagePresentationRegistryInstance = pagePresentationRegistry
            capabilityRegistryInstance = capabilityRegistry
            surfacePolicyInstance = surfacePolicy
            adminSecurityInstance = adminSecurity
            usageStoreInstance = usageStore
            inactivityPolicyInstance = inactivityPolicy
            backupPolicyInstance = backupPolicy
            notificationHostInstance = notificationHost
            officialIdentitiesInstance = officialIdentities
            childExtensionRuntimeInstance = childExtensionRuntime
            val systemPluginController = com.ai.assistance.operit.plugins.system.SystemPluginController(
                context = appContext,
                runtimeRole = runtimeRole,
                uiRegistry = systemUiRegistry,
                hostFactory = { pluginId, role -> createAdmittedSystemHost(pluginId, role) }
            )
            systemPluginController.initialize()
            systemPluginControllerInstance = systemPluginController
            initialized = true
            lifecyclePhase = "initialized"
            AppLogger.i(TAG, "AI Limbs Plugin Platform kernel initialized role=$runtimeRole: ${manager.store.rootDir.absolutePath}")
        }
    }

    suspend fun start(): Unit = startInternal(restoreBusinessRuntime = true)

    /**
     * Establish only the unique Plugin Kernel owner. No plugin or child runtime is restored here.
     * Resident Step 4 uses this entry so later stages can move Bridge, Dispatcher and plugin services
     * without claiming they were already migrated by the owner handoff.
     */
    internal suspend fun startOwnerOnly(): Unit = startInternal(restoreBusinessRuntime = false)

    private suspend fun startInternal(restoreBusinessRuntime: Boolean): Unit = runtimeLifecycleMutex.withLock {
        requireInitialized()
        if (started) {
            check(!restoreBusinessRuntime || businessRuntimeRestored) {
                "Plugin Kernel is owner-only; business runtime restoration belongs to a later migration stage"
            }
            return@withLock
        }
        check(lifecyclePhase == "initialized" || lifecyclePhase == "stopped") {
            "Kernel cannot start from $lifecyclePhase; retire the failed runtime first"
        }
        lifecyclePhase = "starting"
        lifecycleError = null
        businessRuntimeRestored = false
        try {
            if (restoreBusinessRuntime) {
                childExtensionRuntimeInstance.start()
                childRuntimeStarted = true
                if (runtimeRole == PluginRuntimeRole.LEGACY_HOST || runtimeRole == PluginRuntimeRole.BUSINESS) {
                    restoreFoundationalRuntime(requireReady = runtimeRole == PluginRuntimeRole.BUSINESS)
                }
                managerInstance.restoreEnabledPlugins()
                managerInstance.reconcileInactivityPolicy()
                managerInstance.reconcileBackupPolicy()
                businessRuntimeRestored = true
                if (runtimeRole == PluginRuntimeRole.BUSINESS) {
                    residentBridgePluginMounted =
                        managerInstance.snapshot(RESIDENT_BRIDGE_PLUGIN_ID).mountedVersion != null
                    if (residentBridgePluginMounted) {
                        childExtensionRuntimeInstance.awaitEnabledPointReady(RESIDENT_BRIDGE_PROVIDER_POINT)
                        awaitResidentBridgeReady()
                    }
                    residentBridgeIngressPrepared = true
                }
            }
        } catch (error: CancellationException) {
            lifecyclePhase = "start_failed"
            lifecycleError = error.toString().take(2048)
            throw error
        } catch (error: Throwable) {
            lifecyclePhase = "start_failed"
            lifecycleError = error.toString().take(2048)
            AppLogger.e(TAG, "Plugin restore encountered an error", error)
            throw error
        }
        synchronized(lifecycleLock) {
            started = true
        }
        if (businessRuntimeRestored) startInactivityMonitor()
        lifecyclePhase = "running"
        AppLogger.i(
            TAG,
            "AI Limbs Plugin Platform started role=$runtimeRole businessRuntimeRestored=$businessRuntimeRestored"
        )
    }

    /** Verify every desired ingress was handed to its provider; remote connection state is diagnostic. */
    private suspend fun awaitResidentBridgeReady() {
        val record = checkNotNull(contributionsInstance.find(
            PluginContributionKind.PROVIDER, "plugin.bridge.runtime_readiness.v1"
        )) { "Bridge runtime readiness provider is missing; update Bridge before Resident takeover" }
        check(record.ownerPluginId == RESIDENT_BRIDGE_PLUGIN_ID) {
            "Resident Bridge readiness is owned by an unexpected plugin"
        }
        val reader = record.payload as? com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
            ?: error("Bridge runtime readiness provider has an incompatible payload")
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (true) {
            val snapshot = JSONObject(reader.invoke("{}"))
            check(snapshot.getInt("schema") == 1) { "Unsupported Bridge readiness schema" }
            residentBridgeRuntimeReadiness = snapshot
            // A provider may be unconfigured, awaiting authorization or offline. Those are
            // provider states, not failure of the Core-owned Dispatcher/Plugin Kernel. build38
            // retired the entire business runtime when one STOPPED provider coexisted with two
            // ONLINE providers. Validate the generic ingress handoff independently, and retain
            // the original ready/fatal_error/phase fields without reporting false connectivity.
            val providers = snapshot.getJSONArray("providers")
            val providerIds = mutableSetOf<String>()
            var desiredCount = 0
            var requestedCount = 0
            for (index in 0 until providers.length()) {
                val provider = providers.getJSONObject(index)
                val providerId = provider.getString("provider_id")
                check(providerId.isNotBlank() && providerIds.add(providerId)) {
                    "Invalid or duplicate Bridge provider identity: $providerId"
                }
                if (provider.getBoolean("desired_connected")) {
                    check(provider.getBoolean("enabled")) { "Disabled Bridge provider is desired: $providerId" }
                    desiredCount += 1
                    if (provider.getBoolean("start_requested")) requestedCount += 1
                }
            }
            check(providers.length() == snapshot.getInt("provider_count") &&
                desiredCount == snapshot.getInt("desired_provider_count")) {
                "Inconsistent Bridge runtime readiness inventory"
            }
            // An empty Bridge is a valid runtime state: without provider contributions there is
            // intentionally no PluginBridgeManager to create, so manager_ready remains false while
            // the Bridge readiness contract reports ready=true and fatal_error=false. Preserve the
            // stricter manager requirement whenever at least one provider exists.
            val emptyBridgeReady = providers.length() == 0 &&
                desiredCount == 0 &&
                snapshot.getBoolean("ready") &&
                !snapshot.getBoolean("fatal_error")
            val ingressReady = requestedCount == desiredCount &&
                (snapshot.getBoolean("manager_ready") || emptyBridgeReady)
            snapshot.put("ingress_handoff_ready", ingressReady)
            if (ingressReady) {
                if (!snapshot.getBoolean("ready")) {
                    AppLogger.w(TAG, "Bridge ingress handed off with provider connection states: ${snapshot.toString().take(1500)}")
                }
                return
            }
            check(android.os.SystemClock.elapsedRealtime() < deadline) {
                "Resident Bridge ingress handoff timed out: ${snapshot.toString().take(1500)}"
            }
            delay(100L)
        }
    }

    private suspend fun restoreFoundationalRuntime(requireReady: Boolean) {
        foundationalRuntimePhase = FoundationalRuntimePhase.STARTING
        foundationalRuntimeError = null
        try {
            val controller = systemPluginControllerInstance.restore(requireReady = requireReady)
            if (!requireReady) {
                foundationalRuntimePhase = when (controller.phase) {
                    com.ai.assistance.operit.plugins.system.SystemPluginRuntimePhase.STOPPED ->
                        FoundationalRuntimePhase.STOPPED
                    com.ai.assistance.operit.plugins.system.SystemPluginRuntimePhase.STARTING ->
                        FoundationalRuntimePhase.STARTING
                    com.ai.assistance.operit.plugins.system.SystemPluginRuntimePhase.READY ->
                        FoundationalRuntimePhase.READY
                    com.ai.assistance.operit.plugins.system.SystemPluginRuntimePhase.FAILED ->
                        FoundationalRuntimePhase.FAILED
                }
                foundationalRuntimeError = controller.lastError
                return
            }

            check(controller.started && controller.ready && !controller.stopped) {
                "Plugin Center foundational runtime did not become mounted/READY: $controller"
            }
            val delegatedGateway = contributionsInstance.find(
                PluginContributionKind.SERVICE,
                com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_DELEGATED_GATEWAY_SERVICE
            )
            check(
                delegatedGateway != null &&
                    delegatedGateway.ownerPluginId ==
                    com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_PLUGIN_ID
            ) {
                "Plugin Center foundational control-plane is missing required service " +
                    com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_DELEGATED_GATEWAY_SERVICE
            }
            foundationalRuntimePhase = FoundationalRuntimePhase.READY
            foundationalRuntimeError = null
        } catch (error: Throwable) {
            foundationalRuntimePhase = FoundationalRuntimePhase.FAILED
            foundationalRuntimeError = error.toString().take(2048)
            throw error
        }
    }

    private fun foundationalRuntimeSnapshotJson(): JSONObject {
        val controller =
            if (::systemPluginControllerInstance.isInitialized) {
                systemPluginControllerInstance.runtimeSnapshot()
            } else {
                null
            }
        val delegatedGatewayReady =
            if (initialized) {
                contributionsInstance.find(
                    PluginContributionKind.SERVICE,
                    com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_DELEGATED_GATEWAY_SERVICE
                )?.ownerPluginId == com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_PLUGIN_ID
            } else {
                false
            }
        val requiredServiceSatisfied =
            runtimeRole != PluginRuntimeRole.BUSINESS || delegatedGatewayReady
        val controllerPhase = controller?.phase
        val effectivePhase = when {
            controllerPhase == com.ai.assistance.operit.plugins.system.SystemPluginRuntimePhase.FAILED ->
                FoundationalRuntimePhase.FAILED
            controllerPhase == com.ai.assistance.operit.plugins.system.SystemPluginRuntimePhase.STARTING ->
                FoundationalRuntimePhase.STARTING
            controllerPhase == com.ai.assistance.operit.plugins.system.SystemPluginRuntimePhase.READY &&
                !requiredServiceSatisfied ->
                FoundationalRuntimePhase.FAILED
            controllerPhase == com.ai.assistance.operit.plugins.system.SystemPluginRuntimePhase.READY ->
                FoundationalRuntimePhase.READY
            foundationalRuntimePhase == FoundationalRuntimePhase.FAILED ->
                FoundationalRuntimePhase.FAILED
            controllerPhase == com.ai.assistance.operit.plugins.system.SystemPluginRuntimePhase.STOPPED ->
                FoundationalRuntimePhase.STOPPED
            else -> foundationalRuntimePhase
        }
        val ready =
            effectivePhase == FoundationalRuntimePhase.READY &&
                controller?.ready == true &&
                requiredServiceSatisfied
        val synthesizedError =
            if (controller?.ready == true && !requiredServiceSatisfied) {
                "Plugin Center required foundational service is not published: " +
                    com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_DELEGATED_GATEWAY_SERVICE
            } else {
                null
            }
        return JSONObject()
            .put("phase", effectivePhase.name.lowercase())
            .put("started", controller?.started == true)
            .put("ready", ready)
            .put(
                "stopped",
                effectivePhase == FoundationalRuntimePhase.STOPPED && controller?.stopped != false
            )
            .put("controller_phase", controller?.phase?.name?.lowercase() ?: "stopped")
            .put("controller_ready", controller?.ready == true)
            .put("active_version", controller?.activeVersion ?: JSONObject.NULL)
            .put("required_service_ready", delegatedGatewayReady)
            .put(
                "last_error",
                synthesizedError ?: foundationalRuntimeError ?: controller?.lastError ?: JSONObject.NULL
            )
    }

    private fun requireFoundationalRuntimeReady(): JSONObject {
        val snapshot = foundationalRuntimeSnapshotJson()
        if (!snapshot.getBoolean("started") || !snapshot.getBoolean("ready") ||
            snapshot.getBoolean("stopped")) {
            val error = IllegalStateException(
                "Resident foundational runtime readiness was lost: $snapshot"
            )
            foundationalRuntimePhase = FoundationalRuntimePhase.FAILED
            foundationalRuntimeError = error.toString().take(2048)
            throw error
        }
        return snapshot
    }

    internal suspend fun startResidentFoundationalRuntime(): JSONObject =
        runtimeLifecycleMutex.withLock {
            requireInitialized()
            check(runtimeRole == PluginRuntimeRole.BUSINESS) {
                "Resident foundational runtime requires BUSINESS role"
            }
            check(started && !businessRuntimeRestored && !residentBridgeIngressPrepared) {
                "Resident foundational runtime requires owner-only BUSINESS Kernel before Bridge"
            }
            lifecyclePhase = "starting_foundational_runtime"
            lifecycleError = null
            try {
                restoreFoundationalRuntime(requireReady = true)
                val snapshot = requireFoundationalRuntimeReady()
                lifecyclePhase = "running"
                snapshot
            } catch (error: CancellationException) {
                lifecyclePhase = "foundational_runtime_start_failed"
                lifecycleError = error.toString().take(2048)
                throw error
            } catch (error: Throwable) {
                lifecyclePhase = "foundational_runtime_start_failed"
                lifecycleError = error.toString().take(2048)
                AppLogger.e(TAG, "Resident foundational runtime restore failed", error)
                throw error
            }
        }

    internal suspend fun startResidentBridgeIngress(): Boolean = runtimeLifecycleMutex.withLock {
        requireInitialized()
        check(runtimeRole == PluginRuntimeRole.BUSINESS) {
            "Resident Bridge ingress requires BUSINESS runtime"
        }
        check(started && !businessRuntimeRestored) {
            "Resident Bridge ingress requires owner-only BUSINESS Kernel"
        }
        requireFoundationalRuntimeReady()
        if (residentBridgeIngressPrepared) return@withLock residentBridgePluginMounted
        lifecyclePhase = "starting_bridge_ingress"
        lifecycleError = null
        try {
            if (!childRuntimeStarted) {
                childExtensionRuntimeInstance.start()
                childRuntimeStarted = true
            }
            // Foundational Plugin Center control-plane is a previous explicit stage. Bridge never
            // owns or repairs that lifecycle; it only starts after the control-plane is READY.
            residentBridgePluginMounted = managerInstance.restoreEnabledPlugin(RESIDENT_BRIDGE_PLUGIN_ID)
            if (residentBridgePluginMounted) {
                childExtensionRuntimeInstance.awaitEnabledPointReady(RESIDENT_BRIDGE_PROVIDER_POINT)
                awaitResidentBridgeReady()
            }
            residentBridgeIngressPrepared = true
            lifecyclePhase = "running"
            AppLogger.i(
                TAG,
                "Resident Bridge ingress prepared mounted=$residentBridgePluginMounted"
            )
            residentBridgePluginMounted
        } catch (error: CancellationException) {
            lifecyclePhase = "bridge_start_failed"
            lifecycleError = error.toString().take(2048)
            throw error
        } catch (error: Throwable) {
            lifecyclePhase = "bridge_start_failed"
            lifecycleError = error.toString().take(2048)
            AppLogger.e(TAG, "Resident Bridge ingress restore failed", error)
            throw error
        }
    }

    /**
     * Restore the ordinary parent/child plugin business plane inside Resident Core. Presentation
     * registrations stay suppressed; capability/service/provider state belongs to this process.
     */
    internal suspend fun startResidentPluginServices(): org.json.JSONObject = runtimeLifecycleMutex.withLock {
        requireInitialized()
        check(runtimeRole == PluginRuntimeRole.BUSINESS) {
            "Resident plugin services require BUSINESS runtime"
        }
        check(started && residentBridgeIngressPrepared) {
            "Resident plugin services require owner and prepared Bridge ingress"
        }
        requireFoundationalRuntimeReady()
        if (residentPluginServicesPrepared) {
            return@withLock checkNotNull(residentPluginRuntimeReport)
        }
        lifecyclePhase = "starting_plugin_services"
        lifecycleError = null
        try {
            if (!childRuntimeStarted) {
                childExtensionRuntimeInstance.start()
                childRuntimeStarted = true
            }
            // Idempotent for the Bridge parent already mounted by Step 7. All other enabled HOT
            // parent plugins are now restored under the same Core-owned manager/capability registry.
            managerInstance.restoreEnabledPlugins()
            val childReport = childExtensionRuntimeInstance.awaitBusinessChildrenReady()
            managerInstance.reconcileInactivityPolicy()
            managerInstance.reconcileBackupPolicy()

            val pluginSnapshots = managerInstance.snapshots()
            val activePlugins = pluginSnapshots.filter { it.mountedVersion != null }
            val failedEnabled = pluginSnapshots.filter { snapshot ->
                snapshot.persistentState?.let { state ->
                    state.enabled && state.lastState == PluginLifecycleState.FAILED
                } == true
            }
            val blockedEnabled = pluginSnapshots.filter { snapshot ->
                snapshot.persistentState?.let { state ->
                    state.enabled && state.lastState == PluginLifecycleState.BLOCKED
                } == true
            }
            // All registered child extension points use the same mount/readiness contract.
            // Ubuntu fields below remain diagnostics for existing status consumers only.
            residentSubsystemsReady = true
            val ubuntu = childExtensionRuntimeInstance.loggingSnapshots()
                .firstOrNull { it.extensionId == RESIDENT_UBUNTU_EXTENSION_ID }
            residentUbuntuConfigured = ubuntu != null
            val ubuntuRequired = ubuntu?.enabled == true
            val ubuntuCapabilitiesReady =
                capabilityRegistryInstance.activeIds().containsAll(RESIDENT_UBUNTU_REQUIRED_CAPABILITIES)
            residentUbuntuControlReady = !ubuntuRequired ||
                (ubuntu.lifecycle == com.ai.limbs.plugin.runtime.ChildExtensionLifecycle.ACTIVE && ubuntuCapabilitiesReady)
            residentPluginRuntimeReport = org.json.JSONObject()
                .put("active_parent_count", activePlugins.size)
                .put("active_parent_ids", org.json.JSONArray(activePlugins.map { it.pluginId }))
                .put("failed_enabled_parent_ids", org.json.JSONArray(failedEnabled.map { it.pluginId }))
                .put("blocked_enabled_parent_ids", org.json.JSONArray(blockedEnabled.map { it.pluginId }))
                .put("children", childReport)
                .put("subsystems_ready", residentSubsystemsReady)
                .put("capability_count", capabilityRegistryInstance.activeIds().size)
                .put("ubuntu_configured", residentUbuntuConfigured)
                .put("ubuntu_required", ubuntuRequired)
                .put("ubuntu_control_ready", residentUbuntuControlReady)

            businessRuntimeRestored = true
            residentPluginServicesPrepared = true
            startInactivityMonitor()
            lifecyclePhase = "running"
            AppLogger.i(TAG, "Resident plugin services prepared: $residentPluginRuntimeReport")
            checkNotNull(residentPluginRuntimeReport)
        } catch (error: CancellationException) {
            lifecyclePhase = "plugin_services_start_failed"
            lifecycleError = error.toString().take(2048)
            throw error
        } catch (error: Throwable) {
            lifecyclePhase = "plugin_services_start_failed"
            lifecycleError = error.toString().take(2048)
            AppLogger.e(TAG, "Resident plugin service restore failed", error)
            throw error
        }
    }

    private fun startInactivityMonitor() {
        inactivityMonitorJob?.cancel()
        inactivityMonitorJob = monitorScope.launch {
            while (isActive) {
                val policy = inactivityPolicyInstance.snapshot()
                val interval = if (policy.enabled && policy.mode == InactivityThresholdMode.TEST_SECONDS) 1_000L else 60_000L
                delay(interval)
                if (!started) continue
                try {
                    managerInstance.reconcileInactivityPolicy()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    AppLogger.w(TAG, "Inactivity policy check failed", error)
                }
            }
        }
    }

    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        shutdownOwner(null, null, residentHandoff = false)
    }

    internal suspend fun shutdownForResidentHandoff(
        handoff: com.ai.assistance.operit.core.tools.system.resident.ResidentPermissionHandoff?,
        onDestructiveRetirementStarted: () -> Unit
    ): Unit = withContext(NonCancellable) {
        shutdownOwner(handoff, onDestructiveRetirementStarted, residentHandoff = true)
    }

    private suspend fun shutdownOwner(
        handoff: com.ai.assistance.operit.core.tools.system.resident.ResidentPermissionHandoff?,
        onDestructiveRetirementStarted: (() -> Unit)?,
        residentHandoff: Boolean
    ): Unit {
        runtimeLifecycleMutex.withLock {
            if (!initialized || lifecyclePhase == "stopped") return@withLock
            handoff?.verify(checkNotNull(
                com.ai.assistance.operit.core.tools.system.privilege.PrivilegeRuntime.connection()
            ) { "Permission backend is disconnected before runtime handoff" })
            if (residentHandoff) {
                checkNotNull(onDestructiveRetirementStarted) {
                    "Resident handoff retirement requires an explicit destructive boundary callback"
                }.invoke()
            }
            // Validation ends here. Once started flips false this VM may become partially retired
            // and must never resume LEGACY_HOST business ownership.
            started = false
            lifecyclePhase = "stopping"
            val failures = mutableListOf<Throwable>()
            suspend fun retire(action: suspend () -> Unit) {
                try { action() }
                catch (error: Exception) { failures += error }
            }
            retire { inactivityMonitorJob?.cancelAndJoin(); inactivityMonitorJob = null }
            if (childRuntimeStarted) {
                // Stop children before parents so transport handles cannot keep calling a retired
                // Bridge parent. This also covers Step 7's partial BUSINESS restoration.
                retire { childExtensionRuntimeInstance.stop() }
            }
            if (businessRuntimeRestored || residentBridgePluginMounted || residentBridgeIngressPrepared) {
                retire { managerInstance.shutdown(handoff) }
            }
            if (runtimeRole == PluginRuntimeRole.LEGACY_HOST || runtimeRole == PluginRuntimeRole.BUSINESS) {
                // Foundational runtime has an independent lifecycle. It may have started before
                // businessRuntimeRestored=true during Resident staged startup, so never gate its
                // shutdown on the ordinary plugin-plane completion flag.
                retire {
                    try {
                        systemPluginControllerInstance.shutdown()
                        foundationalRuntimePhase = FoundationalRuntimePhase.STOPPED
                        foundationalRuntimeError = null
                    } catch (error: Throwable) {
                        foundationalRuntimePhase = FoundationalRuntimePhase.FAILED
                        foundationalRuntimeError = error.toString().take(2048)
                        throw error
                    }
                }
            }
            retire { notificationHostInstance.clear() }
            if (failures.isNotEmpty()) {
                lifecyclePhase = "stop_failed"
                val failure = IllegalStateException("Plugin kernel retirement failed; process retains ownership")
                failures.forEach(failure::addSuppressed)
                lifecycleError = failures.joinToString("; ") { it.toString() }.take(2048)
                AppLogger.e(TAG, "Plugin shutdown encountered an error", failure)
                throw failure
            }
            lifecyclePhase = "stopped"
            lifecycleError = null
            businessRuntimeRestored = false
            childRuntimeStarted = false
            residentBridgeIngressPrepared = false
            residentBridgeRuntimeReadiness = null
            residentBridgePluginMounted = false
            residentPluginServicesPrepared = false
            residentSubsystemsReady = false
            residentUbuntuControlReady = false
            residentUbuntuConfigured = false
            residentPluginRuntimeReport = null
            // Registries and references still exist in this VM. Only process death releases
            // runtimeOwnerLease; shutdown alone must never authorize another process to mount.
            AppLogger.i(TAG, "AI Limbs Plugin Platform kernel retired; lease retained until process exit")
        }
    }

    private fun requireInitialized() {
        check(initialized) { "AI Limbs Plugin Platform kernel is not initialized" }
    }

    private const val RESIDENT_BRIDGE_PLUGIN_ID = "plugin.system.bridge"
    private const val RESIDENT_BRIDGE_PROVIDER_POINT = "ai_limbs.bridge.provider"
    private const val RESIDENT_UBUNTU_EXTENSION_ID = "ai_limbs.system_environment.ubuntu"
    private val RESIDENT_UBUNTU_REQUIRED_CAPABILITIES = setOf(
        "plugin.ubuntu.command",
        "plugin.ubuntu.process"
    )
}
