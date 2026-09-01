package com.ai.limbs.plugins.bridge

import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeManager
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeProviderCatalog
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderContribution
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelFieldKind
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderNotificationState
import com.ai.limbs.plugin.runtime.ChildExtensionBinder
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessDynamicPanelProvider
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessNotificationAction
import com.ai.limbs.plugin.runtime.InProcessNotificationActionHandler
import com.ai.limbs.plugin.runtime.InProcessNotificationHost
import com.ai.limbs.plugin.runtime.InProcessNotificationState
import com.ai.limbs.plugin.runtime.InProcessPanelAction
import com.ai.limbs.plugin.runtime.InProcessPanelField
import com.ai.limbs.plugin.runtime.InProcessPanelFieldKind
import com.ai.limbs.plugin.runtime.InProcessPanelResult
import com.ai.limbs.plugin.runtime.InProcessPanelState
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessScreenBlock
import com.ai.limbs.plugin.runtime.InProcessSelectionProvider
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    private val contributions = ConcurrentHashMap<String, BridgeProviderContribution>()
    private val panelProvider = BridgeDynamicPanelProvider()
    private val notificationProvider = BridgeNotificationPublisher()
    private var notificationHandle: AutoCloseable? = null
    private var manager: AiLimbsBridgeManager? = null
    private var managerScope: CoroutineScope? = null
    private var pointHandle: AutoCloseable? = null
    fun mount() {
        try {
            val notificationHost =
                host.providers.resolve(InProcessSystemIds.NOTIFICATION_HOST_PROVIDER)?.payload as? InProcessNotificationHost
                    ?: error("AI Limbs Notification Host Surface is not available")
            notificationHandle = notificationHost.publish(
                notificationProvider.state,
                InProcessNotificationActionHandler { actionId -> notificationProvider.perform(actionId) }
            )
            pointHandle = hub.publishPoint(
                ownerPluginId = host.pluginId,
                point = InProcessSystemIds.BRIDGE_PROVIDER_POINT,
                apiVersion = 3,
                title = "Bridge Provider",
                description = "AI Limbs remote Bridge provider contract",
                allowedHostCapabilities = setOf("core.bridge.remote.invoke"),
                binder = ChildExtensionBinder { binding ->
                    val contribution = binding.payload as? BridgeProviderContribution
                        ?: error("Bridge child extension did not publish BridgeProviderContribution")
                    require(contribution.factory.profiles.isNotEmpty()) { "Bridge provider has no profiles" }
                    check(contributions.putIfAbsent(binding.extensionId, contribution) == null) {
                        "Bridge child extension already bound: ${binding.extensionId}"
                    }
                    rebuildManager()
                    AutoCloseable {
                        contributions.remove(binding.extensionId, contribution)
                        rebuildManager()
                    }
                }
            )
            registerCapabilities()
            host.registerProvider(
                PANEL_PROVIDER_ID,
                panelProvider,
                mapOf("kind" to "dynamic_control_panel")
            )
            host.registerScreen(
                InProcessScreen(
                    id = SCREEN_ID,
                    title = "Bridge",
                    description = "V0.6.4.7.8 Bridge Core · Provider 由 .ailx 子插件动态接入",
                    blocks = listOf(
                        InProcessScreenBlock.ChildExtensionInstaller(
                            "添加 Bridge Provider",
                            InProcessSystemIds.BRIDGE_PROVIDER_POINT
                        ),
                        InProcessScreenBlock.ChildExtensionSelector(
                            "当前 Bridge Provider",
                            InProcessSystemIds.BRIDGE_PROVIDER_POINT,
                            SELECT_CAPABILITY,
                            PANEL_PROVIDER_ID
                        ),
                        InProcessScreenBlock.DynamicPanel(PANEL_PROVIDER_ID),
                        InProcessScreenBlock.ChildExtensionList(InProcessSystemIds.BRIDGE_PROVIDER_POINT)
                    )
                )
            )
            host.registerHomeTile(
                InProcessHomeTile(TILE_ID, "Bridge", "可插拔 Bridge Provider", SCREEN_ID)
            )
        } catch (error: Throwable) {
            pointHandle?.close()
            pointHandle = null
            notificationHandle?.close()
            notificationHandle = null
            notificationProvider.clear()
            throw error
        }
    }

    suspend fun stop() {
        pointHandle?.close()
        pointHandle = null
        notificationHandle?.close()
        notificationHandle = null
        synchronized(this) {
            manager?.stopRuntime()
            manager = null
            managerScope?.cancel()
            managerScope = null
            AiLimbsBridgeProviderCatalog.replaceFactories(emptyList())
            contributions.clear()
            panelProvider.clear()
            notificationProvider.clear()
        }
    }

    @Synchronized
    private fun rebuildManager() {
        manager?.stopRuntime()
        managerScope?.cancel()
        manager = null
        managerScope = null
        val values = contributions.values.toList()
        AiLimbsBridgeProviderCatalog.replaceFactories(values.map { it.factory })
        if (values.isEmpty()) {
            panelProvider.clear()
            notificationProvider.clear()
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nextManager = AiLimbsBridgeManager(host.applicationContext, scope)
        managerScope = scope
        manager = nextManager
        scope.launch {
            nextManager.state.collect {
                panelProvider.refresh()
                notificationProvider.refresh()
            }
        }
        panelProvider.refresh()
        notificationProvider.refresh()
    }
    private fun registerCapabilities() {
        host.registerCapability(
            SELECT_CAPABILITY,
            "选择 Bridge Provider",
            executor = InProcessCapabilityExecutor { raw ->
                val extensionId = JSONObject(raw).getString("extension_id")
                val contribution = contributions[extensionId]
                    ?: error("Bridge extension is not active: $extensionId")
                val profile = contribution.factory.profiles.firstOrNull()
                    ?: error("Bridge extension has no profile")
                requireManager().selectProvider(profile.id)
                recordUseCompat(extensionId)
                panelProvider.refresh()
                notificationProvider.refresh()
                JSONObject()
                    .put("success", true)
                    .put("content", "已切换至 ${profile.label}")
                    .put("provider_id", profile.id)
                    .put("label", profile.label)
                    .toString()
            }
        )
    }

    private fun selectedEntry(): Map.Entry<String, BridgeProviderContribution>? {
        val profileId = manager?.activeProfile?.id ?: return null
        return contributions.entries.firstOrNull { (_, contribution) ->
            contribution.factory.profiles.any { it.id == profileId }
        }
    }

    private fun requireManager(): AiLimbsBridgeManager =
        manager ?: error("No Bridge Provider is active")

    private fun recordUseCompat(extensionId: String) {
        try {
            hub.recordUse(extensionId)
        } catch (_: LinkageError) {
            // Usage accounting is optional when an older Extension Hub is still installed.
        }
    }

    private fun controlFor(current: AiLimbsBridgeManager): BridgeProviderControl =
        object : BridgeProviderControl {
            override val profile
                get() = current.activeProfile
            override val state
                get() = current.state.value
            override val availableActions
                get() = current.availableActions()

            override fun perform(action: com.ai.assistance.operit.integrations.ailimbs.BridgeAction): Boolean =
                current.perform(action)

            override fun statusSummary(): String = current.statusSummary()
        }

    private inner class BridgeDynamicPanelProvider : InProcessDynamicPanelProvider, InProcessSelectionProvider {
        private val mutableState = MutableStateFlow<InProcessPanelState?>(null)
        private val mutableSelectedId = MutableStateFlow<String?>(null)
        override val state: StateFlow<InProcessPanelState?> = mutableState.asStateFlow()
        override val selectedId: StateFlow<String?> = mutableSelectedId.asStateFlow()

        fun clear() {
            mutableState.value = null
            mutableSelectedId.value = null
        }

        fun refresh() {
            val currentManager = manager ?: run {
                clear()
                return
            }
            val selected = selectedEntry() ?: run {
                clear()
                return
            }
            mutableSelectedId.value = selected.key
            val panel = selected.value.panel.snapshot(
                host.applicationContext,
                controlFor(currentManager)
            )
            mutableState.value = panel.toInProcessState()
        }

        override suspend fun perform(
            actionId: String,
            fieldValues: Map<String, String>
        ): InProcessPanelResult {
            val currentManager = requireManager()
            val selected = selectedEntry()
                ?: error("Selected Bridge Provider contribution is missing")
            recordUseCompat(selected.key)
            val result = selected.value.panel.perform(
                host.applicationContext,
                actionId,
                fieldValues,
                controlFor(currentManager)
            )
            refresh()
            return InProcessPanelResult(
                message = result.message,
                fieldValues = result.fieldValues
            )
        }
    }

    private inner class BridgeNotificationPublisher {
        private val mutableState = MutableStateFlow<InProcessNotificationState?>(null)
        val state: StateFlow<InProcessNotificationState?> = mutableState.asStateFlow()

        fun clear() {
            mutableState.value = null
        }

        fun refresh() {
            val currentManager = manager ?: run { clear(); return }
            val selected = selectedEntry() ?: run { clear(); return }
            val notification = selected.value.notification ?: run { clear(); return }
            mutableState.value = notification.snapshot(
                host.applicationContext,
                controlFor(currentManager)
            ).toInProcessState()
        }

        suspend fun perform(actionId: String) {
            val currentManager = requireManager()
            val selected = selectedEntry()
                ?: error("Selected Bridge Provider contribution is missing")
            val notification = selected.value.notification
                ?: error("Selected Bridge Provider has no notification contribution")
            recordUseCompat(selected.key)
            notification.perform(
                host.applicationContext,
                actionId,
                controlFor(currentManager)
            )
            panelProvider.refresh()
            refresh()
        }
    }

    private fun BridgeProviderNotificationState.toInProcessState() =
        InProcessNotificationState(
            title = title,
            summary = summary,
            statusLines = statusLines,
            actions = actions.map { action ->
                InProcessNotificationAction(
                    id = action.id,
                    label = action.label,
                    priority = action.priority,
                    enabled = action.enabled
                )
            }
        )

    private fun com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelState.toInProcessState() =
        InProcessPanelState(
            title = title,
            description = description,
            statusLines = statusLines,
            fields = fields.map { field ->
                InProcessPanelField(
                    id = field.id,
                    label = field.label,
                    kind = if (field.kind == BridgeProviderPanelFieldKind.SECRET) {
                        InProcessPanelFieldKind.SECRET
                    } else {
                        InProcessPanelFieldKind.TEXT
                    },
                    value = field.value,
                    placeholder = field.placeholder,
                    enabled = field.enabled
                )
            },
            actions = actions.map { action ->
                InProcessPanelAction(
                    id = action.id,
                    label = action.label,
                    enabled = action.enabled,
                    requiredFieldIds = action.requiredFieldIds
                )
            }
        )

    companion object {
        private const val SCREEN_ID = "plugin.system.bridge.screen"
        private const val TILE_ID = "plugin.system.bridge.tile"
        private const val SELECT_CAPABILITY = "plugin.bridge.select_provider"
        private const val PANEL_PROVIDER_ID = "plugin.bridge.control_panel"
    }
}
