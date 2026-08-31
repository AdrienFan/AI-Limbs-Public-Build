package com.ai.limbs.plugins.bridge

import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeManager
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeProviderCatalog
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderFactory
import com.ai.limbs.plugin.runtime.ChildExtensionBinder
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessScreenBlock
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject

class BridgePluginEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        val hub = host.providers.resolve(InProcessSystemIds.EXTENSION_HUB_PROVIDER)?.payload as? ExtensionHubService
            ?: error("Plugin Extension Hub is not active")
        val runtime = BridgeRuntime(host, hub)
        runtime.mount()
        return InProcessPluginHandle { runtime.stop() }
    }
}

private class BridgeRuntime(
    private val host: InProcessPluginHost,
    private val hub: ExtensionHubService
) {
    private val factories = ConcurrentHashMap<String, BridgeProviderFactory>()
    private var manager: AiLimbsBridgeManager? = null
    private var managerScope: CoroutineScope? = null
    private var pointHandle: AutoCloseable? = null

    fun mount() {
        pointHandle = hub.publishPoint(
            ownerPluginId = host.pluginId,
            point = InProcessSystemIds.BRIDGE_PROVIDER_POINT,
            apiVersion = 1,
            title = "Bridge Provider",
            description = "AI Limbs remote Bridge provider contract",
            allowedHostCapabilities = setOf("core.bridge.remote.invoke"),
            binder = ChildExtensionBinder { binding ->
                val factory = binding.payload as? BridgeProviderFactory
                    ?: error("Bridge child extension did not publish BridgeProviderFactory")
                require(factory.profiles.isNotEmpty()) { "Bridge provider has no profiles" }
                check(factories.putIfAbsent(binding.extensionId, factory) == null) {
                    "Bridge child extension already bound: ${binding.extensionId}"
                }
                rebuildManager()
                AutoCloseable {
                    factories.remove(binding.extensionId, factory)
                    rebuildManager()
                }
            }
        )
        registerCapabilities()
        host.registerScreen(
            InProcessScreen(
                id = SCREEN_ID,
                title = "Bridge",
                description = "V0.6.4.7.8 Bridge Core · Provider 由 .ailx 子插件动态接入",
                blocks = listOf(
                    InProcessScreenBlock.ChildExtensionInstaller("添加 Bridge Provider", InProcessSystemIds.BRIDGE_PROVIDER_POINT),
                    InProcessScreenBlock.ChildExtensionSelector("当前 Bridge Provider", InProcessSystemIds.BRIDGE_PROVIDER_POINT, SELECT_CAPABILITY),
                    InProcessScreenBlock.ChildExtensionList(InProcessSystemIds.BRIDGE_PROVIDER_POINT),
                    InProcessScreenBlock.CapabilityButton("连接", CONNECT_CAPABILITY),
                    InProcessScreenBlock.CapabilityButton("停止", STOP_CAPABILITY),
                    InProcessScreenBlock.CapabilityButton("重连", RECONNECT_CAPABILITY),
                    InProcessScreenBlock.CapabilityButton("刷新 / Liveness", REFRESH_CAPABILITY),
                    InProcessScreenBlock.CapabilityButton("查看 Bridge 状态", STATUS_CAPABILITY)
                )
            )
        )
        host.registerHomeTile(InProcessHomeTile(TILE_ID, "Bridge", "可插拔 Bridge Provider", SCREEN_ID))
    }

    suspend fun stop() {
        pointHandle?.close(); pointHandle = null
        synchronized(this) {
            manager?.stopRuntime(); manager = null
            managerScope?.cancel(); managerScope = null
            AiLimbsBridgeProviderCatalog.replaceFactories(emptyList())
            factories.clear()
        }
    }

    @Synchronized
    private fun rebuildManager() {
        manager?.stopRuntime()
        managerScope?.cancel()
        manager = null; managerScope = null
        val values = factories.values.toList()
        AiLimbsBridgeProviderCatalog.replaceFactories(values)
        if (values.isEmpty()) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        managerScope = scope
        manager = AiLimbsBridgeManager(host.applicationContext, scope)
    }

    private fun registerCapabilities() {
        host.registerCapability(SELECT_CAPABILITY, "选择 Bridge Provider", executor = InProcessCapabilityExecutor { raw ->
            val extensionId = JSONObject(raw).getString("extension_id")
            val factory = factories[extensionId] ?: error("Bridge extension is not active: $extensionId")
            val profile = factory.profiles.firstOrNull() ?: error("Bridge extension has no profile")
            requireManager().selectProvider(profile.id)
            JSONObject().put("success", true).put("provider_id", profile.id).put("label", profile.label).toString()
        })
        host.registerCapability(CONNECT_CAPABILITY, "连接 Bridge", executor = simple { it.connect(); "connect requested" })
        host.registerCapability(STOP_CAPABILITY, "停止 Bridge", executor = simple { it.stopByUser(); "stop requested" })
        host.registerCapability(RECONNECT_CAPABILITY, "重连 Bridge", executor = simple { it.reconnect(); "reconnect requested" })
        host.registerCapability(REFRESH_CAPABILITY, "刷新 Bridge", executor = simple { it.verifyLiveness(); "liveness checked" })
        host.registerCapability(STATUS_CAPABILITY, "Bridge 状态", executor = InProcessCapabilityExecutor {
            val current = synchronized(this) { manager }
            if (current == null) {
                JSONObject().put("content", "尚未安装或启用任何 Bridge Provider").toString()
            } else {
                JSONObject().put("content", current.statusSummary())
                    .put("active_profile", current.activeProfile.id)
                    .put("active_label", current.activeProfile.label).toString()
            }
        })
    }

    private fun simple(action: (AiLimbsBridgeManager) -> String) = InProcessCapabilityExecutor {
        val manager = requireManager()
        JSONObject().put("success", true).put("content", action(manager)).toString()
    }
    private fun requireManager(): AiLimbsBridgeManager = synchronized(this) { manager } ?: error("No Bridge Provider is active")

    companion object {
        private const val SCREEN_ID = "plugin.system.bridge.screen"
        private const val TILE_ID = "plugin.system.bridge.tile"
        private const val SELECT_CAPABILITY = "plugin.bridge.select_provider"
        private const val CONNECT_CAPABILITY = "plugin.bridge.connect"
        private const val STOP_CAPABILITY = "plugin.bridge.stop"
        private const val RECONNECT_CAPABILITY = "plugin.bridge.reconnect"
        private const val REFRESH_CAPABILITY = "plugin.bridge.refresh"
        private const val STATUS_CAPABILITY = "plugin.bridge.status"
    }
}
