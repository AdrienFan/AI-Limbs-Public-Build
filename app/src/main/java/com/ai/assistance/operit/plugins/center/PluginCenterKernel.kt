package com.ai.assistance.operit.plugins.center

import android.content.Context
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsPluginCapabilityRegistry
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsPluginRuntimeCapabilityInvokerFactory
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgRuntimeAdapter
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException

/**
 * Process-wide host for the AI Limbs Plugin Center kernel.
 *
 * initialize() creates the managed store and registries.
 * start() restores enabled hot plugins after built-in adapters have registered.
 * shutdown() releases mounted runtimes without clearing their enabled preference.
 */
object PluginCenterKernel {
    private const val TAG = "PluginCenterKernel"
    private val lifecycleLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var started = false

    private lateinit var managerInstance: PluginManager
    private lateinit var runtimeAdaptersInstance: PluginRuntimeAdapterRegistry
    private lateinit var contributionsInstance: PluginContributionRegistry
    private lateinit var extensionPointsInstance: ExtensionPointRegistry
    private lateinit var extensionRouterInstance: ExtensionRouter
    private lateinit var controlPlaneInstance: PluginControlPlane

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

    fun initialize(
        context: Context,
        secretBroker: PluginSecretBroker = NoApprovedPluginSecretBroker
    ) {
        synchronized(lifecycleLock) {
            if (initialized) return

            val appContext = context.applicationContext
            val runtimeAdapters = PluginRuntimeAdapterRegistry().apply {
                register(NoopPluginRuntimeAdapter)
                register(ToolPkgRuntimeAdapter)
            }
            val contributions = PluginContributionRegistry()
            val extensionPoints = ExtensionPointRegistry().apply {
                register(
                    ExtensionPointDefinition(
                        point = PluginExtensionPoints.TEST_PROVIDER,
                        apiVersion = 1,
                        binder = { AutoCloseable { } }
                    )
                )
            }
            val extensionRouter = ExtensionRouter(extensionPoints)
            val runtimeHost = PluginRuntimeHost()
            val pluginContextFactory =
                PluginContextFactory(
                    contributions = contributions,
                    eventBusHost = PluginEventBusHost(),
                    capabilityInvokerFactory = AiLimbsPluginRuntimeCapabilityInvokerFactory(appContext),
                    secretBroker = secretBroker
                )
            val manager =
                PluginManager(
                    appContext = appContext,
                    store = PluginStore.fromContext(appContext),
                    trustVerifier = StrictPluginTrustVerifier,
                    runtimeAdapters = runtimeAdapters,
                    contributions = contributions,
                    extensionRouter = extensionRouter,
                    capabilityBinder = AiLimbsPluginCapabilityRegistry,
                    runtimeHost = runtimeHost,
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
            initialized = true
            AppLogger.i(TAG, "Plugin Center kernel initialized: ${manager.store.rootDir.absolutePath}")
        }
    }

    suspend fun start() {
        requireInitialized()
        synchronized(lifecycleLock) {
            if (started) return
        }

        try {
            controlPlaneInstance.restoreEnabledPlugins()
            AppLogger.i(TAG, "Plugin Center kernel restored enabled plugins")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // One damaged plugin must not prevent AI Limbs itself from starting.
            AppLogger.e(TAG, "Plugin Center restore encountered an error", error)
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
            AppLogger.i(TAG, "Plugin Center kernel shut down")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLogger.e(TAG, "Plugin Center shutdown encountered an error", error)
        }
    }

    private fun requireInitialized() {
        check(initialized) { "Plugin Center kernel is not initialized" }
    }
}
