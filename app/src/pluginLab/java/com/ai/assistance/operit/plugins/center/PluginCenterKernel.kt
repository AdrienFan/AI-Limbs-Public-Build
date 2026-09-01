package com.ai.assistance.operit.plugins.center

import android.content.Context
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
 * AI Limbs Plugin Center micro-kernel. The base owns lifecycle, trust, permissions and routing only.
 * Every optional capability or screen must arrive through a mounted plugin contribution.
 */
object PluginCenterKernel {
    private const val TAG = "PluginCenterKernel"
    private val lifecycleLock = Any()

    @Volatile private var initialized = false
    @Volatile private var started = false

    private lateinit var managerInstance: PluginManager
    private lateinit var runtimeAdaptersInstance: PluginRuntimeAdapterRegistry
    private lateinit var contributionsInstance: PluginContributionRegistry
    private lateinit var extensionPointsInstance: ExtensionPointRegistry
    private lateinit var extensionRouterInstance: ExtensionRouter
    private lateinit var controlPlaneInstance: PluginControlPlane
    private lateinit var uiRegistryInstance: PluginUiRegistry
    private lateinit var capabilityRegistryInstance: PluginHostCapabilityRegistry
    private lateinit var surfacePolicyInstance: HostSurfacePolicy
    private lateinit var adminSecurityInstance: AdminSecurityManager
    private lateinit var usageStoreInstance: PluginUsageStore
    private lateinit var inactivityPolicyInstance: PluginInactivityPolicyStore
    private lateinit var backupPolicyInstance: PluginBackupPolicyStore
    private lateinit var notificationHostInstance: PluginNotificationHost
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var inactivityMonitorJob: Job? = null

    val isInitialized: Boolean get() = initialized
    val isStarted: Boolean get() = started
    val controlPlane: PluginControlPlane
        get() = requireInitialized().let { controlPlaneInstance }

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
    internal val capabilities: PluginHostCapabilityRegistry
        get() = requireInitialized().let { capabilityRegistryInstance }
    val hostSurfacePolicy: HostSurfacePolicy
        get() = requireInitialized().let { surfacePolicyInstance }
    val adminSecurity: AdminSecurityManager
        get() = requireInitialized().let { adminSecurityInstance }

    fun dispatchNotificationAction(bindingId: String, actionId: String): Boolean {
        requireInitialized()
        return notificationHostInstance.dispatch(bindingId, actionId)
    }

    fun refreshNotifications() {
        requireInitialized()
        notificationHostInstance.refreshAll()
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
            val capabilityRegistry = PluginHostCapabilityRegistry(surfacePolicy, usageStore)
            val contributions = PluginContributionRegistry()
            surfacePolicy.register(
                HostSurfaceDefinition(
                    id = PluginSurfaceIds.HOST_NOTIFICATION,
                    title = "通知宿主 · ai_limbs.notification.surface@1",
                    detail = "允许插件发布受宿主管控的通知状态与最多两个快捷动作",
                    requiredScope = PluginNotificationHost.NOTIFICATION_SCOPE,
                    kind = HostSurfaceKind.HOST_PROVIDER,
                    publicContracts = listOf(
                        "InProcessNotificationHost",
                        "InProcessNotificationState",
                        "InProcessNotificationAction",
                        "InProcessNotificationActionHandler",
                        "InProcessSystemIds.NOTIFICATION_HOST_PROVIDER"
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
                val contracts = when (point) {
                    PluginExtensionPoints.UI_HOME_TILE -> listOf(
                        "InProcessHomeTile",
                        "InProcessPluginHost.registerHomeTile"
                    )
                    PluginExtensionPoints.UI_SCREEN -> listOf(
                        "InProcessScreen",
                        "InProcessScreenBlock",
                        "InProcessDynamicPanelProvider",
                        "InProcessPanelState",
                        "InProcessPanelField",
                        "InProcessPanelAction",
                        "InProcessSelectionProvider",
                        "InProcessPluginHost.registerScreen"
                    )
                    else -> emptyList()
                }
                surfacePolicy.register(
                    HostSurfaceDefinition(
                        id = PluginSurfaceIds.extension(point),
                        title = "$title · $point@1",
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
                        apiVersion = 1,
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
                secretBroker = secretBroker
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
            val controlPlane = PluginControlPlane(
                manager,
                extensionPoints,
                extensionRouter,
                contributions,
                surfacePolicy,
                inactivityPolicy,
                backupPolicy,
                onInactivityPolicyChanged = { startInactivityMonitor() }
            )

            manager.initialize()
            managerInstance = manager
            runtimeAdaptersInstance = runtimeAdapters
            contributionsInstance = contributions
            extensionPointsInstance = extensionPoints
            extensionRouterInstance = extensionRouter
            controlPlaneInstance = controlPlane
            uiRegistryInstance = uiRegistry
            capabilityRegistryInstance = capabilityRegistry
            surfacePolicyInstance = surfacePolicy
            adminSecurityInstance = adminSecurity
            usageStoreInstance = usageStore
            inactivityPolicyInstance = inactivityPolicy
            backupPolicyInstance = backupPolicy
            notificationHostInstance = notificationHost
            initialized = true
            AppLogger.i(TAG, "AI Limbs Plugin Center kernel initialized: ${manager.store.rootDir.absolutePath}")
        }
    }

    suspend fun start() {
        requireInitialized()
        synchronized(lifecycleLock) {
            if (started) return
        }
        try {
            controlPlaneInstance.restoreEnabledPlugins()
            controlPlaneInstance.runInactivityCheck()
            controlPlaneInstance.runBackupCheck()
            AppLogger.i(TAG, "AI Limbs Plugin Center restored enabled plugins")
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
            controlPlaneInstance.shutdown()
            notificationHostInstance.clear()
            AppLogger.i(TAG, "AI Limbs Plugin Center kernel shut down")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Plugin shutdown encountered an error", error)
        }
    }

    private fun requireInitialized() {
        check(initialized) { "AI Limbs Plugin Center kernel is not initialized" }
    }
}
