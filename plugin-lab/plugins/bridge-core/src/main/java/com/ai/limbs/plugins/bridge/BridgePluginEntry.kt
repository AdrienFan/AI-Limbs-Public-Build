package com.ai.limbs.plugins.bridge

import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeManager
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeState
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeProviderCatalog
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProfile
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private data class BridgePresentationState(
        val revision: Long = 0L,
        val selectedExtensionId: String? = null,
        val panel: InProcessPanelState? = null,
        val notification: InProcessNotificationState? = null
    )

    private val contributions = ConcurrentHashMap<String, BridgeProviderContribution>()
    private val presentationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutablePresentation = MutableStateFlow(BridgePresentationState())
    private val presentation = mutablePresentation.asStateFlow()
    private var presentationRevision = 0L
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
                    description = "AI Limbs Bridge Core · Provider 由 .ailx 子插件动态接入",
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
            clearPresentation()
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
            clearPresentation()
            presentationScope.cancel()
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
            clearPresentation()
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nextManager = AiLimbsBridgeManager(host.applicationContext, scope)
        managerScope = scope
        manager = nextManager
        publishPresentation(nextManager, nextManager.state.value)
        scope.launch {
            nextManager.state.collect { bridgeState ->
                publishPresentation(nextManager, bridgeState)
            }
        }
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
                val currentManager = requireManager()
                currentManager.selectProvider(profile.id)
                recordUseCompat(extensionId)
                publishPresentation(currentManager, currentManager.state.value)
                JSONObject()
                    .put("success", true)
                    .put("content", "已切换至 ${profile.label}")
                    .put("provider_id", profile.id)
                    .put("label", profile.label)
                    .toString()
            }
        )
    }

    private fun selectedEntry(currentManager: AiLimbsBridgeManager): Map.Entry<String, BridgeProviderContribution>? {
        val profileId = currentManager.activeProfile.id
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

    private fun liveControlFor(current: AiLimbsBridgeManager): BridgeProviderControl =
        object : BridgeProviderControl {
            override val profile: BridgeProfile
                get() = current.activeProfile
            override val state: AiLimbsBridgeState
                get() = current.state.value
            override val availableActions: List<BridgeAction>
                get() = current.availableActions()

            override fun perform(action: BridgeAction): Boolean = current.perform(action)
            override fun statusSummary(): String = current.statusSummary()
        }

    private fun snapshotControlFor(
        current: AiLimbsBridgeManager,
        bridgeState: AiLimbsBridgeState
    ): BridgeProviderControl {
        val capturedProfile = current.activeProfile
        val capturedActions = current.availableActions(bridgeState)
        val capturedSummary = current.statusSummary()
        return object : BridgeProviderControl {
            override val profile: BridgeProfile = capturedProfile
            override val state: AiLimbsBridgeState = bridgeState
            override val availableActions: List<BridgeAction> = capturedActions

            override fun perform(action: BridgeAction): Boolean = current.perform(action)
            override fun statusSummary(): String = capturedSummary
        }
    }

    @Synchronized
    private fun clearPresentation() {
        mutablePresentation.value = BridgePresentationState(revision = ++presentationRevision)
    }

    @Synchronized
    private fun publishPresentation(
        currentManager: AiLimbsBridgeManager,
        bridgeState: AiLimbsBridgeState
    ) {
        if (manager !== currentManager) return
        val selected = selectedEntry(currentManager) ?: run {
            clearPresentation()
            return
        }
        val control = snapshotControlFor(currentManager, bridgeState)
        val panel = selected.value.panel.snapshot(host.applicationContext, control).toInProcessState()
        val notification = selected.value.notification
            ?.snapshot(host.applicationContext, control)
            ?.toInProcessState()
        mutablePresentation.value = BridgePresentationState(
            revision = ++presentationRevision,
            selectedExtensionId = selected.key,
            panel = panel,
            notification = notification
        )
    }

    private inner class BridgeDynamicPanelProvider : InProcessDynamicPanelProvider, InProcessSelectionProvider {
        override val state: StateFlow<InProcessPanelState?> = presentation
            .map { it.panel }
            .stateIn(presentationScope, SharingStarted.Eagerly, presentation.value.panel)
        override val selectedId: StateFlow<String?> = presentation
            .map { it.selectedExtensionId }
            .stateIn(presentationScope, SharingStarted.Eagerly, presentation.value.selectedExtensionId)

        override suspend fun perform(
            actionId: String,
            fieldValues: Map<String, String>
        ): InProcessPanelResult {
            val currentManager = requireManager()
            val selected = selectedEntry(currentManager)
                ?: error("Selected Bridge Provider contribution is missing")
            recordUseCompat(selected.key)
            val result = selected.value.panel.perform(
                host.applicationContext,
                actionId,
                fieldValues,
                liveControlFor(currentManager)
            )
            publishPresentation(currentManager, currentManager.state.value)
            return InProcessPanelResult(
                message = result.message,
                fieldValues = result.fieldValues
            )
        }
    }

    private inner class BridgeNotificationPublisher {
        val state: StateFlow<InProcessNotificationState?> = presentation
            .map { it.notification }
            .stateIn(presentationScope, SharingStarted.Eagerly, presentation.value.notification)

        suspend fun perform(actionId: String) {
            val currentManager = requireManager()
            val selected = selectedEntry(currentManager)
                ?: error("Selected Bridge Provider contribution is missing")
            val notification = selected.value.notification
                ?: error("Selected Bridge Provider has no notification contribution")
            recordUseCompat(selected.key)
            notification.perform(
                host.applicationContext,
                actionId,
                liveControlFor(currentManager)
            )
            publishPresentation(currentManager, currentManager.state.value)
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
