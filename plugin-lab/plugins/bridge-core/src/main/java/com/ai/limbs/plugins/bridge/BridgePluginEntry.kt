package com.ai.limbs.plugins.bridge

import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeManager
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeState
import com.ai.assistance.operit.integrations.ailimbs.AiLimbsBridgeProviderCatalog
import com.ai.assistance.operit.integrations.ailimbs.BridgeAction
import com.ai.assistance.operit.integrations.ailimbs.BridgeProfile
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderContribution
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderUiSlots
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderControl
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelFieldKind
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderPanelState
import com.ai.assistance.operit.integrations.ailimbs.BridgeProviderNotificationState
import com.ai.limbs.plugin.runtime.ChildExtensionBinder
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessHomeTile
import com.ai.limbs.plugin.runtime.InProcessNotificationAction
import com.ai.limbs.plugin.runtime.InProcessNotificationActionHandler
import com.ai.limbs.plugin.runtime.InProcessNotificationHost
import com.ai.limbs.plugin.runtime.InProcessNotificationState
import com.ai.limbs.plugin.runtime.InProcessPluginEntry
import com.ai.limbs.plugin.runtime.InProcessPluginHandle
import com.ai.limbs.plugin.runtime.InProcessPluginHost
import com.ai.limbs.plugin.runtime.InProcessScreen
import com.ai.limbs.plugin.runtime.InProcessSystemIds
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class BridgePluginEntry : InProcessPluginEntry {
    override suspend fun mount(host: InProcessPluginHost): InProcessPluginHandle {
        val runtime = BridgeRuntime(host)
        runtime.mount()
        return InProcessPluginHandle { runtime.stop() }
    }
}

private class BridgeRuntime(
    private val host: InProcessPluginHost
) {
    private data class BridgePresentationState(
        val revision: Long = 0L,
        val selectedExtensionId: String? = null,
        val panel: BridgeProviderPanelState? = null,
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
    private var hubWatcher: Job? = null
    @Volatile
    private var activeHub: ExtensionHubService? = null
    private var hubGeneration = 0L
    private var stopped = false
    fun mount() {
        try {
            val notificationHost =
                host.providers.resolve(InProcessSystemIds.NOTIFICATION_HOST_PROVIDER)?.payload as? InProcessNotificationHost
                    ?: error("AI Limbs Notification Host Surface is not available")
            notificationHandle = notificationHost.publish(
                notificationProvider.state,
                InProcessNotificationActionHandler { actionId -> notificationProvider.perform(actionId) }
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
                    // UI component semantics are owned by Plugin Center, not by the Host SDK.
                    // Bridge only declares what it needs and keeps all provider business logic here.
                    schemaId = PLUGIN_CENTER_UI_SCHEMA,
                    documentJson = JSONObject()
                        .put("schema", 1)
                        .put(
                            "blocks",
                            JSONArray()
                                .put(JSONObject()
                                    .put("type", "child_extension_installer")
                                    .put("label", "添加 Bridge Provider")
                                    .put("point", InProcessSystemIds.BRIDGE_PROVIDER_POINT))
                                .put(JSONObject()
                                    .put("type", "child_extension_selector")
                                    .put("label", "当前 Bridge Provider")
                                    .put("point", InProcessSystemIds.BRIDGE_PROVIDER_POINT)
                                    .put("select_capability_id", SELECT_CAPABILITY)
                                    .put("selection_provider_id", PANEL_PROVIDER_ID))
                                .put(
                                    JSONObject()
                                        // The generic component remains owned by Plugin Center. This wrapper
                                        // changes only this Bridge screen instance and opens one child slot.
                                        .put("type", "component_slot")
                                        .put("id", BridgeProviderUiSlots.PROVIDER_PANEL_COMPONENT_ID)
                                        .put(
                                            "component",
                                            JSONObject()
                                                .put("type", "dynamic_panel")
                                                .put("provider_id", PANEL_PROVIDER_ID)
                                        )
                                        .put(
                                            "child_slots",
                                            JSONObject().put(
                                                BridgeProviderUiSlots.PROVIDER_PANEL_AFTER,
                                                JSONObject().put(
                                                    "points",
                                                    JSONArray().put(InProcessSystemIds.BRIDGE_PROVIDER_POINT)
                                                )
                                            )
                                        )
                                )
                                .put(JSONObject().put("type", "child_extension_list").put("point", InProcessSystemIds.BRIDGE_PROVIDER_POINT))
                        )
                        .toString()
                )
            )
            host.registerHomeTile(
                InProcessHomeTile(TILE_ID, "Bridge", "可插拔 Bridge Provider", SCREEN_ID)
            )
            startHubWatcher()
        } catch (error: Throwable) {
            hubWatcher?.cancel()
            hubWatcher = null
            pointHandle?.close()
            pointHandle = null
            notificationHandle?.close()
            notificationHandle = null
            clearPresentation()
            throw error
        }
    }

    private fun startHubWatcher() {
        hubWatcher?.cancel()
        hubWatcher = presentationScope.launch {
            host.providers.observe(InProcessSystemIds.EXTENSION_HUB_PROVIDER).collectLatest { binding ->
                val observedHub = binding?.payload as? ExtensionHubService
                while (currentCoroutineContext().isActive) {
                    val failure = runCatching { switchExtensionHub(observedHub) }.exceptionOrNull()
                    if (failure == null) break
                    if (failure is CancellationException) throw failure
                    delay(HUB_ATTACH_RETRY_MS)
                    val latestHub = host.providers
                        .resolve(InProcessSystemIds.EXTENSION_HUB_PROVIDER)
                        ?.payload as? ExtensionHubService
                    if (latestHub !== observedHub) break
                }
            }
        }
    }

    @Synchronized
    private fun switchExtensionHub(nextHub: ExtensionHubService?) {
        if (stopped) return
        if (activeHub === nextHub && (nextHub == null || pointHandle != null)) return

        hubGeneration += 1L
        val previousPoint = pointHandle
        pointHandle = null
        activeHub = null
        try {
            previousPoint?.close()
        } finally {
            clearContributionsLocked()
        }
        if (nextHub == null) return

        activeHub = nextHub
        val generation = hubGeneration
        try {
            pointHandle = nextHub.publishPoint(
                ownerPluginId = host.pluginId,
                point = InProcessSystemIds.BRIDGE_PROVIDER_POINT,
                apiVersion = 3,
                title = "Bridge Provider",
                description = "AI Limbs remote Bridge provider contract",
                allowedHostCapabilities = setOf("core.bridge.remote.invoke"),
                binder = ChildExtensionBinder { binding ->
                    bindContribution(generation, binding.extensionId, binding.payload)
                }
            )
        } catch (error: Throwable) {
            activeHub = null
            hubGeneration += 1L
            runCatching { clearContributionsLocked() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private fun bindContribution(
        generation: Long,
        extensionId: String,
        payload: Any
    ): AutoCloseable {
        val contribution = payload as? BridgeProviderContribution
            ?: error("Bridge child extension did not publish BridgeProviderContribution")
        require(contribution.factory.profiles.isNotEmpty()) { "Bridge provider has no profiles" }
        synchronized(this) {
            check(!stopped && generation == hubGeneration && activeHub != null) {
                "Bridge extension point is no longer active"
            }
            check(contributions.putIfAbsent(extensionId, contribution) == null) {
                "Bridge child extension already bound: $extensionId"
            }
            try {
                rebuildManager()
            } catch (error: Throwable) {
                contributions.remove(extensionId, contribution)
                runCatching { rebuildManager() }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
                throw error
            }
        }
        return AutoCloseable {
            synchronized(this) {
                if (contributions.remove(extensionId, contribution)) rebuildManager()
            }
        }
    }

    private fun clearContributionsLocked() {
        if (contributions.isEmpty() && manager == null) return
        contributions.clear()
        rebuildManager()
    }

    suspend fun stop() {
        hubWatcher?.cancel()
        hubWatcher = null
        synchronized(this) {
            stopped = true
            hubGeneration += 1L
            val publishedPoint = pointHandle
            pointHandle = null
            activeHub = null
            contributions.clear()
            runCatching { publishedPoint?.close() }
            manager?.stopRuntime()
            manager = null
            managerScope?.cancel()
            managerScope = null
            AiLimbsBridgeProviderCatalog.replaceFactories(emptyList())
            clearPresentation()
        }
        notificationHandle?.close()
        notificationHandle = null
        presentationScope.cancel()
    }

    @Synchronized
    private fun rebuildManager() {
        manager?.stopRuntime()
        managerScope?.cancel()
        manager = null
        managerScope = null
        val values = contributions.entries
            .sortedBy { it.key }
            .map { it.value }
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
        val currentHub = activeHub ?: return
        try {
            currentHub.recordUse(extensionId)
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
        val panel = selected.value.panel.snapshot(host.applicationContext, control)
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

    /**
     * Exposes Bridge presentation through the generic Plugin Center UI state/event channel.
     *
     * Bridge still owns the business-level BridgeProviderPanelState contract. Only this adapter turns
     * that state into Plugin Center schema JSON, so Stable Kernel never learns Bridge field types.
     */
    private inner class BridgeDynamicPanelProvider : InProcessUiStateProvider {
        override val stateJson: StateFlow<String?> = presentation
            .map { it.toPluginCenterUiStateJson() }
            .stateIn(
                presentationScope,
                SharingStarted.Eagerly,
                presentation.value.toPluginCenterUiStateJson()
            )

        override suspend fun perform(eventId: String, payloadJson: String): String {
            val fieldValues = JSONObject(payloadJson)
                .optJSONObject("field_values")
                .toStringMap()
            val currentManager = requireManager()
            val selected = selectedEntry(currentManager)
                ?: error("Selected Bridge Provider contribution is missing")
            recordUseCompat(selected.key)
            val result = selected.value.panel.perform(
                host.applicationContext,
                eventId,
                fieldValues,
                liveControlFor(currentManager)
            )
            publishPresentation(currentManager, currentManager.state.value)
            return JSONObject()
                .put("message", result.message)
                .put("field_values", JSONObject(result.fieldValues))
                .toString()
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

    /**
     * Serializes the current Bridge panel into Plugin Center schema v1.
     * Field kinds such as `secret` live in this schema now; adding a future Bridge-specific visual
     * field no longer requires changing an AI Limbs Host enum.
     */
    private fun BridgePresentationState.toPluginCenterUiStateJson(): String? {
        if (selectedExtensionId == null && panel == null) return null
        val root = JSONObject()
            .put("schema", 1)
            .put("panel_available", panel != null)
        selectedExtensionId?.let { root.put("selected_id", it) }
        panel?.let { current ->
            root.put("title", current.title)
                .put("description", current.description)
                .put("status_lines", JSONArray(current.statusLines))
                .put("fields", JSONArray().apply {
                    current.fields.forEach { field ->
                        put(JSONObject()
                            .put("id", field.id)
                            .put("label", field.label)
                            .put("kind", if (field.kind == BridgeProviderPanelFieldKind.SECRET) "secret" else "text")
                            .put("value", field.value)
                            .put("placeholder", field.placeholder)
                            .put("enabled", field.enabled))
                    }
                })
                .put("actions", JSONArray().apply {
                    current.actions.forEach { action ->
                        put(JSONObject()
                            .put("id", action.id)
                            .put("label", action.label)
                            .put("enabled", action.enabled)
                            .put("required_field_ids", JSONArray(action.requiredFieldIds.toList())))
                    }
                })
        }
        return root.toString()
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap { keys().forEach { key -> put(key, optString(key)) } }
    }

    companion object {
        private const val SCREEN_ID = BridgeProviderUiSlots.SCREEN_ID
        private const val TILE_ID = "plugin.system.bridge.tile"
        private const val SELECT_CAPABILITY = "plugin.bridge.select_provider"
        private const val PANEL_PROVIDER_ID = "plugin.bridge.control_panel"
        // Component schema is versioned by Plugin Center; adding new controls must not change Host ABI.
        private const val PLUGIN_CENTER_UI_SCHEMA = "ai_limbs.plugin_center.ui.v1"
        private const val HUB_ATTACH_RETRY_MS = 1_000L
    }
}
