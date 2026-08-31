package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.plugins.lab.DeclarativePluginRuntimeAdapter
import com.ai.assistance.operit.plugins.lab.LabCapabilityRegistry
import com.ai.assistance.operit.plugins.lab.PluginHomeTileSpec
import com.ai.assistance.operit.plugins.lab.PluginScreenSpec
import com.ai.assistance.operit.plugins.lab.PluginUiRegistry
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException

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

    fun initialize(
        context: Context,
        secretBroker: PluginSecretBroker = NoApprovedPluginSecretBroker
    ) {
        synchronized(lifecycleLock) {
            if (initialized) return
            val appContext = context.applicationContext
            val uiRegistry = PluginUiRegistry()
            val capabilityRegistry = LabCapabilityRegistry()
            val runtimeAdapters = PluginRuntimeAdapterRegistry().apply {
                register(NoopPluginRuntimeAdapter)
                register(DeclarativePluginRuntimeAdapter)
            }
            val contributions = PluginContributionRegistry()
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
            }
            val extensionRouter = ExtensionRouter(extensionPoints)
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
                runtimeHost = PluginRuntimeHost(),
                pluginContextFactory = pluginContextFactory
            )
            val controlPlane = PluginControlPlane(manager, extensionPoints, extensionRouter)

            manager.initialize()
            managerInstance = manager
            runtimeAdaptersInstance = runtimeAdapters
            contributionsInstance = contributions
            extensionPointsInstance = extensionPoints
            extensionRouterInstance = extensionRouter
            controlPlaneInstance = controlPlane
            uiRegistryInstance = uiRegistry
            capabilityRegistryInstance = capabilityRegistry
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
            AppLogger.i(TAG, "Plugin Lab restored enabled plugins")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Plugin restore encountered an error", error)
        }
        synchronized(lifecycleLock) {
            started = true
        }
    }

    suspend fun shutdown() {
        if (!initialized) return
        synchronized(lifecycleLock) {
            started = false
        }
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
