package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.plugins.system.KernelPluginPlatformControlV1
import com.ai.assistance.operit.plugins.system.KernelSystemHostGatewayV1
import com.ai.assistance.operit.plugins.system.KernelSystemPluginDelegatedCapabilityInvokerV2
import com.ai.assistance.operit.plugins.system.KernelSystemPluginProviderDirectoryV2
import com.ai.assistance.operit.plugins.system.KernelSystemPluginHostV2
import com.ai.assistance.operit.plugins.system.KernelSystemPluginServicePublisherV2
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemPluginProtocolV1
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

    @Volatile private var initialized = false
    @Volatile private var started = false

    private lateinit var appContextInstance: Context
    private lateinit var managerInstance: PluginManager
    private lateinit var runtimeAdaptersInstance: PluginRuntimeAdapterRegistry
    private lateinit var contributionsInstance: PluginContributionRegistry
    private lateinit var extensionPointsInstance: ExtensionPointRegistry
    private lateinit var extensionRouterInstance: ExtensionRouter
    private lateinit var uiRegistryInstance: PluginUiRegistry
    private lateinit var systemUiRegistryInstance: SystemPluginUiRegistry
    private lateinit var dynamicNavigationRegistryInstance: DynamicNavigationSurfaceRegistry
    private lateinit var capabilityRegistryInstance: PluginHostCapabilityRegistry
    private lateinit var surfacePolicyInstance: HostSurfacePolicy
    private lateinit var adminSecurityInstance: AdminSecurityManager
    private lateinit var usageStoreInstance: PluginUsageStore
    private lateinit var inactivityPolicyInstance: PluginInactivityPolicyStore
    private lateinit var backupPolicyInstance: PluginBackupPolicyStore
    private lateinit var notificationHostInstance: PluginNotificationHost
    private lateinit var systemPluginControllerInstance: com.ai.assistance.operit.plugins.system.SystemPluginController
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var inactivityMonitorJob: Job? = null

    val isInitialized: Boolean get() = initialized
    val isStarted: Boolean get() = started
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
    internal val uiRegistry: PluginUiRegistry
        get() = requireInitialized().let { uiRegistryInstance }
    internal val systemUiRegistry: SystemPluginUiRegistry
        get() = requireInitialized().let { systemUiRegistryInstance }
    internal val dynamicNavigationRegistry: DynamicNavigationSurfaceRegistry
        get() = requireInitialized().let { dynamicNavigationRegistryInstance }
    internal val systemPlugins: com.ai.assistance.operit.plugins.system.SystemPluginController
        get() = requireInitialized().let { systemPluginControllerInstance }
    internal val capabilities: PluginHostCapabilityRegistry
        get() = requireInitialized().let { capabilityRegistryInstance }
    val hostSurfacePolicy: HostSurfacePolicy
        get() = requireInitialized().let { surfacePolicyInstance }
    val adminSecurity: AdminSecurityManager
        get() = requireInitialized().let { adminSecurityInstance }

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
        val pluginAdmin = com.ai.assistance.operit.plugins.system.KernelPluginAdminJsonServiceV1(
            context = appContextInstance,
            manager = managerInstance,
            surfacePolicy = surfacePolicyInstance,
            inactivityPolicy = inactivityPolicyInstance,
            backupPolicy = backupPolicyInstance
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
            providers
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
        secretBroker: PluginSecretBroker = NoApprovedPluginSecretBroker
    ) {
        synchronized(lifecycleLock) {
            if (initialized) return
            val appContext = context.applicationContext
            val surfacePolicy = HostSurfacePolicy(appContext)
            val adminSecurity = AdminSecurityManager(appContext)
            val usageStore = PluginUsageStore(appContext)
            val inactivityPolicy = PluginInactivityPolicyStore(appContext)
            val backupPolicy = PluginBackupPolicyStore(appContext)
            val uiRegistry = PluginUiRegistry()
            val systemUiRegistry = SystemPluginUiRegistry()
            val dynamicNavigationRegistry = DynamicNavigationSurfaceRegistry(appContext)
            val capabilityRegistry = PluginHostCapabilityRegistry(appContext, surfacePolicy, usageStore)
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
            val notificationHost = PluginNotificationHost(appContext, surfacePolicy)
            val runtimeAdapters = PluginRuntimeAdapterRegistry().apply {
                register(NoopPluginRuntimeAdapter)
                register(DeclarativePluginRuntimeAdapter)
                register(AndroidInProcessPluginRuntimeAdapter(contributions, notificationHost))
            }
            listOf(
                Triple(PluginExtensionPoints.UI_HOME_TILE, "首页入口", "允许插件向 AI Limbs 首页添加入口"),
                Triple(PluginExtensionPoints.UI_SCREEN, "插件页面", "允许插件提供可打开的界面页面"),
                Triple(PluginExtensionPoints.UI_THEME, "全局主题 / 皮肤", "允许插件实时接管宿主主题与配色")
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
            }
            val extensionRouter = ExtensionRouter(extensionPoints, surfacePolicy)
            val pluginContextFactory = PluginContextFactory(
                contributions = contributions,
                eventBusHost = PluginEventBusHost(),
                capabilityInvokerFactory = capabilityRegistry,
                secretBroker = secretBroker,
                surfacePolicy = surfacePolicy
            )
            val pluginStore = PluginStore.fromContext(appContext)
            val backupStore = PluginBackupStore(pluginStore)
            val manager = PluginManager(
                appContext = appContext,
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
                runtimeHost = PluginRuntimeHost(),
                pluginContextFactory = pluginContextFactory
            )
            manager.initialize()
            appContextInstance = appContext
            managerInstance = manager
            runtimeAdaptersInstance = runtimeAdapters
            contributionsInstance = contributions
            extensionPointsInstance = extensionPoints
            extensionRouterInstance = extensionRouter
            uiRegistryInstance = uiRegistry
            systemUiRegistryInstance = systemUiRegistry
            dynamicNavigationRegistryInstance = dynamicNavigationRegistry
            capabilityRegistryInstance = capabilityRegistry
            surfacePolicyInstance = surfacePolicy
            adminSecurityInstance = adminSecurity
            usageStoreInstance = usageStore
            inactivityPolicyInstance = inactivityPolicy
            backupPolicyInstance = backupPolicy
            notificationHostInstance = notificationHost
            val systemPluginController = com.ai.assistance.operit.plugins.system.SystemPluginController(
                context = appContext,
                uiRegistry = systemUiRegistry,
                hostFactory = { pluginId, role -> createAdmittedSystemHost(pluginId, role) }
            )
            systemPluginController.initialize()
            systemPluginControllerInstance = systemPluginController
            initialized = true
            AppLogger.i(TAG, "AI Limbs Plugin Platform kernel initialized: ${manager.store.rootDir.absolutePath}")
        }
    }

    suspend fun start() {
        requireInitialized()
        synchronized(lifecycleLock) {
            if (started) return
        }
        try {
            systemPluginControllerInstance.restore()
            managerInstance.restoreEnabledPlugins()
            managerInstance.reconcileInactivityPolicy()
            managerInstance.reconcileBackupPolicy()
            AppLogger.i(TAG, "AI Limbs Plugin Platform restored system Plugin Center before enabled plugins")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Plugin restore encountered an error", error)
        }
        synchronized(lifecycleLock) {
            started = true
        }
        startInactivityMonitor()
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

    suspend fun shutdown() {
        if (!initialized) return
        synchronized(lifecycleLock) {
            started = false
        }
        inactivityMonitorJob?.cancel()
        inactivityMonitorJob = null
        try {
            managerInstance.shutdown()
            systemPluginControllerInstance.shutdown()
            notificationHostInstance.clear()
            AppLogger.i(TAG, "AI Limbs Plugin Platform kernel shut down")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Plugin shutdown encountered an error", error)
        }
    }

    private fun requireInitialized() {
        check(initialized) { "AI Limbs Plugin Platform kernel is not initialized" }
    }
}
