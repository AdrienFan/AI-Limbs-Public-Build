package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.plugins.lab.DeclarativePluginRuntimeAdapter
import com.ai.assistance.operit.plugins.lab.AndroidInProcessPluginRuntimeAdapter
import com.ai.assistance.operit.plugins.lab.LabCapabilityRegistry
import com.ai.assistance.operit.plugins.lab.PluginHomeTileSpec
import com.ai.assistance.operit.plugins.lab.PluginScreenSpec
import com.ai.assistance.operit.plugins.lab.PluginThemeSpec
import com.ai.assistance.operit.plugins.lab.PluginUiRegistry
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
 * Plugin Lab micro-kernel. The base owns lifecycle, trust, permissions and routing only.
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
    private lateinit var capabilityRegistryInstance: LabCapabilityRegistry
    private lateinit var surfacePolicyInstance: HostSurfacePolicy
    private lateinit var adminSecurityInstance: AdminSecurityManager
    private lateinit var usageStoreInstance: PluginUsageStore
    private lateinit var inactivityPolicyInstance: PluginInactivityPolicyStore
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
    internal val capabilities: LabCapabilityRegistry
        get() = requireInitialized().let { capabilityRegistryInstance }
    val hostSurfacePolicy: HostSurfacePolicy
        get() = requireInitialized().let { surfacePolicyInstance }
    val adminSecurity: AdminSecurityManager
        get() = requireInitialized().let { adminSecurityInstance }

    fun recordPluginUse(pluginId: String) {
        requireInitialized()
        usageStoreInstance.recordUse(pluginId)
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
            val uiRegistry = PluginUiRegistry()
            val capabilityRegistry = LabCapabilityRegistry(surfacePolicy, usageStore)
            val contributions = PluginContributionRegistry()
            val runtimeAdapters = PluginRuntimeAdapterRegistry().apply {
                register(NoopPluginRuntimeAdapter)
                register(DeclarativePluginRuntimeAdapter)
                register(AndroidInProcessPluginRuntimeAdapter(contributions))
            }
            listOf(
                Triple(PluginExtensionPoints.UI_HOME_TILE, "首页入口", "允许插件向 Plugin Lab 首页添加入口"),
                Triple(PluginExtensionPoints.UI_SCREEN, "插件页面", "允许插件提供可打开的界面页面"),
                Triple(PluginExtensionPoints.UI_THEME, "全局主题 / 皮肤", "允许插件实时接管宿主主题与配色")
            ).forEach { (point, title, detail) ->
                surfacePolicy.register(
                    HostSurfaceDefinition(
                        id = PluginSurfaceIds.extension(point),
                        title = "$title · $point@1",
                        detail = detail,
                        kind = HostSurfaceKind.EXTENSION_POINT
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
            val manager = PluginManager(
                appContext = appContext,
                store = PluginStore.fromContext(appContext),
                trustVerifier = StrictPluginTrustVerifier,
                runtimeAdapters = runtimeAdapters,
                contributions = contributions,
                extensionRouter = extensionRouter,
                capabilityBinder = capabilityRegistry,
                surfacePolicy = surfacePolicy,
                usageStore = usageStore,
                inactivityPolicy = inactivityPolicy,
                runtimeHost = PluginRuntimeHost(),
                pluginContextFactory = pluginContextFactory
            )
            val controlPlane = PluginControlPlane(
                manager,
                extensionPoints,
                extensionRouter,
                surfacePolicy,
                inactivityPolicy,
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
            initialized = true
            AppLogger.i(TAG, "Plugin Lab kernel initialized: ${manager.store.rootDir.absolutePath}")
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
            AppLogger.i(TAG, "Plugin Lab restored enabled plugins")
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
            AppLogger.i(TAG, "Plugin Lab kernel shut down")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Plugin shutdown encountered an error", error)
        }
    }

    private fun requireInitialized() {
        check(initialized) { "Plugin Lab kernel is not initialized" }
    }
}
