package com.ai.assistance.operit.plugins.center

import android.content.Context
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

/**
 * Stable host-side plugin platform kernel. It owns plugin runtime, policy, storage and registries.
 * Plugin Center UI is intentionally not part of this object; system plugins consume versioned host contracts.
 */
internal object PluginPlatformKernel {
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
    @Volatile private var childRuntimeStarted = false
    @Volatile private var residentBridgeIngressPrepared = false
    @Volatile private var residentBridgePluginMounted = false
    @Volatile private var residentPluginServicesPrepared = false
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
        return org.json.JSONObject()
            .put("phase", lifecyclePhase)
            .put("runtime_role", runtimeRole.name.lowercase())
            .put("initialized", initialized)
            .put("started", started)
            .put("pid", android.os.Process.myPid())
            .put("uid", android.os.Process.myUid())
            .put("owner_lease_held", runtimeOwnerLease != null)
            .put("business_runtime_restored", businessRuntimeRestored)
            .put("child_runtime_started", childRuntimeStarted)
            .put("resident_bridge_ingress_prepared", residentBridgeIngressPrepared)
            .put("resident_bridge_plugin_mounted", residentBridgePluginMounted)
            .put("resident_plugin_services_prepared", residentPluginServicesPrepared)
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
    private lateinit var childExtensionRuntimeInstance: ChildExtensionRuntime
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
            registry = systemUiRegistryInstance
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
                com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_PLUGIN_ID,
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
                uiRegistry,
                pagePresentationRegistry,
                loggingService
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
                register(AndroidInProcessPluginRuntimeAdapter(contributions, notificationHost, officialIdentities) { childExtensionRuntimeInstance })
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
            val childExtensionRuntime = ChildExtensionRuntime(
                appContext = appContext,
                runtimeRole = runtimeRole,
                pluginStore = pluginStore,
                contributions = contributions,
                capabilityRegistry = capabilityRegistry
            )
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
                if (runtimeRole == PluginRuntimeRole.LEGACY_HOST) {
                    systemPluginControllerInstance.restore()
                }
                managerInstance.restoreEnabledPlugins()
                managerInstance.reconcileInactivityPolicy()
                managerInstance.reconcileBackupPolicy()
                businessRuntimeRestored = true
                if (runtimeRole == PluginRuntimeRole.BUSINESS) {
                    residentBridgeIngressPrepared = true
                    residentBridgePluginMounted =
                        managerInstance.snapshot(RESIDENT_BRIDGE_PLUGIN_ID).mountedVersion != null
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

    internal suspend fun startResidentBridgeIngress(): Boolean = runtimeLifecycleMutex.withLock {
        requireInitialized()
        check(runtimeRole == PluginRuntimeRole.BUSINESS) {
            "Resident Bridge ingress requires BUSINESS runtime"
        }
        check(started && !businessRuntimeRestored) {
            "Resident Bridge ingress requires owner-only BUSINESS Kernel"
        }
        if (residentBridgeIngressPrepared) return@withLock residentBridgePluginMounted
        lifecyclePhase = "starting_bridge_ingress"
        lifecycleError = null
        try {
            if (!childRuntimeStarted) {
                childExtensionRuntimeInstance.start()
                childRuntimeStarted = true
            }
            residentBridgePluginMounted = managerInstance.restoreEnabledPlugin(RESIDENT_BRIDGE_PLUGIN_ID)
            if (residentBridgePluginMounted) {
                childExtensionRuntimeInstance.awaitEnabledPointReady(RESIDENT_BRIDGE_PROVIDER_POINT)
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
            "Resident plugin services require a running owner and prepared Bridge ingress"
        }
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
                .put("capability_count", capabilityRegistryInstance.activeIds().size)
                .put("ubuntu_configured", residentUbuntuConfigured)
                .put("ubuntu_required", ubuntuRequired)
                .put("ubuntu_control_ready", residentUbuntuControlReady)
            check(residentUbuntuControlReady) {
                "Enabled Ubuntu subsystem did not become Core-owned: lifecycle=${ubuntu?.lifecycle} capabilities=$ubuntuCapabilitiesReady error=${ubuntu?.lastError}"
            }

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
        shutdownOwner(null)
    }

    internal suspend fun shutdownForResidentHandoff(
        handoff: com.ai.assistance.operit.core.tools.system.resident.ResidentPermissionHandoff
    ): Unit = withContext(NonCancellable) {
        shutdownOwner(handoff)
    }

    private suspend fun shutdownOwner(
        handoff: com.ai.assistance.operit.core.tools.system.resident.ResidentPermissionHandoff?
    ): Unit {
        runtimeLifecycleMutex.withLock {
            if (!initialized || lifecyclePhase == "stopped") return@withLock
            handoff?.verify(checkNotNull(
                com.ai.assistance.operit.core.tools.system.privilege.PrivilegeRuntime.connection()
            ) { "Permission backend is disconnected before runtime handoff" })
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
            if (businessRuntimeRestored && runtimeRole == PluginRuntimeRole.LEGACY_HOST) {
                // Plugin Center is a Host UI system plugin. BUSINESS never restored it, so Core
                // retirement must not manufacture a UI-system-plugin lifecycle on the way out.
                retire { systemPluginControllerInstance.shutdown() }
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
            residentBridgePluginMounted = false
            residentPluginServicesPrepared = false
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
