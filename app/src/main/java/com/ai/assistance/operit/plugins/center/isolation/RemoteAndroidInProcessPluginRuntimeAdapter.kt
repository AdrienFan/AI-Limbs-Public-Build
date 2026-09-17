package com.ai.assistance.operit.plugins.center.isolation

import com.ai.assistance.operit.plugins.center.PluginCapabilityDomain
import com.ai.assistance.operit.plugins.center.PluginCapabilityEffect
import com.ai.assistance.operit.plugins.center.PluginCapabilityExecutor
import com.ai.assistance.operit.plugins.center.PluginCapabilityParameterSpec
import com.ai.assistance.operit.plugins.center.PluginCapabilityReceipt
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.PluginExtensionPoints
import com.ai.assistance.operit.plugins.center.PluginHomeTileSpec
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginRuntimeAdapter
import com.ai.assistance.operit.plugins.center.PluginRuntimeAdapterContext
import com.ai.assistance.operit.plugins.center.PluginRuntimeHandle
import com.ai.assistance.operit.plugins.center.PluginRuntimeRole
import com.ai.assistance.operit.plugins.center.PluginScreenSpec
import com.ai.assistance.operit.plugins.center.PluginThemeMode
import com.ai.assistance.operit.plugins.center.PluginThemeSpec
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * BUSINESS-only adapter that keeps every android_inprocess Dex/JNI payload out of Resident Core.
 * Core owns lifecycle, policy and proxy registrations; ail_plugin_runtime owns executable objects.
 */
internal class RemoteAndroidInProcessPluginRuntimeAdapter : PluginRuntimeAdapter {
    override val kind: String = "android_inprocess"

    override suspend fun mount(context: PluginRuntimeAdapterContext): PluginRuntimeHandle {
        check(context.runtimeRole == PluginRuntimeRole.BUSINESS) {
            "Remote android_inprocess adapter is reserved for BUSINESS runtime"
        }
        val proxy = MountedRemotePlugin(context)
        proxy.mountAndRegister()
        return proxy
    }

    private class MountedRemotePlugin(
        private val context: PluginRuntimeAdapterContext
    ) : PluginRuntimeHandle {
        private val pluginId = context.manifest.pluginId
        private val version = context.manifest.version
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mountMutex = Mutex()
        @Volatile private var sessionId: String? = null
        @Volatile private var closed = false
        private var providerRefreshJob: Job? = null
        private val uiProviders = linkedMapOf<String, RemoteUiStateProvider>()

        suspend fun mountAndRegister() {
            val snapshot = ensureMounted(force = true)
            registerCapabilities(snapshot.optJSONArray("capabilities") ?: JSONArray())
            registerExtensions(snapshot.optJSONArray("extensions") ?: JSONArray())
            registerProviders(snapshot.optJSONArray("providers") ?: JSONArray())
            providerRefreshJob = scope.launch {
                while (isActive) {
                    delay(1_000L)
                    runCatching { refreshUiProviders() }
                }
            }
        }

        override suspend fun stop() = stopRemote(ownerShutdown = false)

        override suspend fun stopForOwnerShutdown() = stopRemote(ownerShutdown = true)

        private suspend fun stopRemote(ownerShutdown: Boolean) {
            closed = true
            providerRefreshJob?.cancel()
            scope.cancel()
            val current = runCatching { PluginRuntimeController.status(context.appContext) }.getOrNull()
            val sid = sessionId
            if (sid != null &&
                current?.optBoolean("available", false) == true &&
                current.optString("session_id") == sid
            ) {
                runCatching {
                    PluginRuntimeWire.request(
                        "stop_plugin",
                        sid,
                        JSONObject()
                            .put("plugin_id", pluginId)
                            .put("owner_shutdown", ownerShutdown),
                        PluginRuntimeWire.BUSINESS_TIMEOUT_MS
                    )
                }.getOrElse { error ->
                    throw PluginInstallException(
                        "PLUGIN_WORKER_STOP_FAILED",
                        "Isolated plugin failed to stop: $pluginId",
                        error
                    )
                }
            }
        }

        private suspend fun ensureMounted(force: Boolean = false): JSONObject = mountMutex.withLock {
            check(!closed) { "Remote plugin runtime is closed: $pluginId" }
            val state = PluginRuntimeController.probe(context.appContext)
            check(state.optBoolean("consistent", false)) { "Plugin runtime is not attested: $state" }
            val currentSession = state.getString("session_id")
            if (force || sessionId != currentSession) {
                val result = PluginRuntimeWire.request(
                    "mount",
                    currentSession,
                    JSONObject().put("plugin_id", pluginId).put("version", version),
                    PluginRuntimeWire.BUSINESS_TIMEOUT_MS
                )
                sessionId = currentSession
                return@withLock result.getJSONObject("operation_result")
            }
            val result = PluginRuntimeWire.request(
                "snapshot_plugin",
                currentSession,
                JSONObject().put("plugin_id", pluginId),
                PluginRuntimeWire.BUSINESS_TIMEOUT_MS
            )
            result.getJSONObject("operation_result")
        }

        private suspend fun invokeCapability(capabilityId: String, parameters: JSONObject): JSONObject {
            ensureMounted()
            val sid = checkNotNull(sessionId)
            val result = PluginRuntimeWire.request(
                "invoke_capability",
                sid,
                JSONObject()
                    .put("plugin_id", pluginId)
                    .put("capability_id", capabilityId)
                    .put("parameters", JSONObject(parameters.toString())),
                PluginRuntimeWire.BUSINESS_TIMEOUT_MS
            )
            return result.getJSONObject("operation_result")
        }

        private fun registerCapabilities(descriptors: JSONArray) {
            for (index in 0 until descriptors.length()) {
                val d = descriptors.getJSONObject(index)
                check(d.getString("owner_id") == pluginId) { "Worker capability owner mismatch" }
                val id = d.getString("id")
                val parameters = buildList {
                    val array = d.optJSONArray("parameters") ?: JSONArray()
                    for (p in 0 until array.length()) {
                        val item = array.getJSONObject(p)
                        add(
                            PluginCapabilityParameterSpec(
                                name = item.getString("name"),
                                type = item.getString("type"),
                                description = item.optString("description", ""),
                                required = item.optBoolean("required", true),
                                default = item.optNullableString("default")
                            )
                        )
                    }
                }
                context.payloadContext.registrar.registerCapability(
                    id,
                    PluginCapabilitySpec(
                        displayName = d.getString("display_name"),
                        description = d.optString("description", ""),
                        invokeAliases = d.stringList("invoke_aliases"),
                        keywords = d.stringList("keywords"),
                        parameters = parameters,
                        suggestedParamsJson = d.optNullableString("suggested_params_json"),
                        inputSchema = d.optNullableString("input_schema"),
                        effect = PluginCapabilityEffect.valueOf(d.getString("effect")),
                        domain = PluginCapabilityDomain.valueOf(d.getString("domain")),
                        workContextRequiredReceipts = d.stringList("work_receipts")
                            .mapTo(linkedSetOf()) { PluginCapabilityReceipt.valueOf(it) },
                        executor = PluginCapabilityExecutor { parametersJson ->
                            invokeCapability(id, parametersJson)
                        }
                    )
                )
            }
        }

        private fun registerExtensions(descriptors: JSONArray) {
            for (index in 0 until descriptors.length()) {
                val d = descriptors.getJSONObject(index)
                val id = d.getString("id")
                when (d.getString("kind")) {
                    "home_tile" -> context.payloadContext.registrar.registerExtension(
                        PluginExtensionPoints.UI_HOME_TILE,
                        id,
                        PluginHomeTileSpec(
                            ownerPluginId = pluginId,
                            id = id,
                            title = d.getString("title"),
                            description = d.optString("description", ""),
                            screenId = d.getString("screen_id")
                        )
                    )
                    "screen" -> context.payloadContext.registrar.registerExtension(
                        PluginExtensionPoints.UI_SCREEN,
                        id,
                        PluginScreenSpec(
                            ownerPluginId = pluginId,
                            id = id,
                            title = d.getString("title"),
                            description = d.optNullableString("description"),
                            schemaId = d.getString("schema_id"),
                            documentJson = d.getString("document_json")
                        )
                    )
                    "theme" -> context.payloadContext.registrar.registerExtension(
                        PluginExtensionPoints.UI_THEME,
                        id,
                        PluginThemeSpec(
                            ownerPluginId = pluginId,
                            id = id,
                            mode = PluginThemeMode.valueOf(d.getString("mode")),
                            pureBlack = d.optBoolean("pure_black", false),
                            colors = d.optJSONObject("colors")?.toStringMap().orEmpty(),
                            backgroundGradient = d.stringList("background_gradient")
                        )
                    )
                    else -> throw PluginInstallException(
                        "PLUGIN_WORKER_EXTENSION_UNSUPPORTED",
                        "Worker returned unsupported cross-process extension kind: ${d.getString("kind")}"
                    )
                }
            }
        }

        private fun registerProviders(descriptors: JSONArray) {
            for (index in 0 until descriptors.length()) {
                val d = descriptors.getJSONObject(index)
                val id = d.getString("id")
                val metadata = d.optJSONObject("metadata")?.toStringMap().orEmpty()
                when (d.getString("kind")) {
                    "capability_executor" -> context.payloadContext.registrar.registerProvider(
                        id,
                        InProcessCapabilityExecutor { parametersJson ->
                            ensureMounted()
                            val sid = checkNotNull(sessionId)
                            val result = PluginRuntimeWire.request(
                                "provider_executor",
                                sid,
                                JSONObject()
                                    .put("plugin_id", pluginId)
                                    .put("provider_id", id)
                                    .put("parameters_json", parametersJson),
                                PluginRuntimeWire.BUSINESS_TIMEOUT_MS
                            )
                            result.getJSONObject("operation_result").getString("result_json")
                        },
                        metadata
                    )
                    "ui_state" -> {
                        val provider = RemoteUiStateProvider(id, d.optNullableString("state_json"))
                        uiProviders[id] = provider
                        context.payloadContext.registrar.registerProvider(id, provider, metadata)
                    }
                    "page_local", "worker_local" -> Unit
                    else -> throw PluginInstallException(
                        "PLUGIN_WORKER_PROVIDER_UNSUPPORTED",
                        "Worker returned unsupported provider kind: ${d.getString("kind")}"
                    )
                }
            }
        }

        private suspend fun refreshUiProviders() {
            if (closed) return
            val snapshot = ensureMounted()
            if (uiProviders.isEmpty()) return
            val descriptors = snapshot.optJSONArray("providers") ?: return
            for (index in 0 until descriptors.length()) {
                val d = descriptors.getJSONObject(index)
                if (d.optString("kind") == "ui_state") {
                    uiProviders[d.getString("id")]?.update(d.optNullableString("state_json"))
                }
            }
        }

        private inner class RemoteUiStateProvider(
            private val providerId: String,
            initial: String?
        ) : InProcessUiStateProvider {
            private val mutable = MutableStateFlow(initial)
            override val stateJson: StateFlow<String?> = mutable

            fun update(value: String?) {
                mutable.value = value
            }

            override suspend fun perform(eventId: String, payloadJson: String): String {
                ensureMounted()
                val sid = checkNotNull(sessionId)
                val result = PluginRuntimeWire.request(
                    "provider_ui_event",
                    sid,
                    JSONObject()
                        .put("plugin_id", pluginId)
                        .put("provider_id", providerId)
                        .put("event_id", eventId)
                        .put("payload_json", payloadJson),
                    PluginRuntimeWire.BUSINESS_TIMEOUT_MS
                )
                refreshUiProviders()
                return result.getJSONObject("operation_result").getString("result_json")
            }
        }
    }
}

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.stringList(key: String): List<String> = buildList {
    val array = optJSONArray(key) ?: return@buildList
    for (index in 0 until array.length()) add(array.getString(index))
}

private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        put(key, optString(key))
    }
}
