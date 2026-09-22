package com.ai.assistance.operit.plugins.center.isolation

import com.ai.assistance.operit.plugins.center.PluginCapabilityDomain
import com.ai.assistance.operit.plugins.center.PluginCapabilityEffect
import com.ai.assistance.operit.plugins.center.PluginCapabilityExecutor
import com.ai.assistance.operit.plugins.center.PluginCapabilityParameterSpec
import com.ai.assistance.operit.plugins.center.PluginCapabilityReceipt
import com.ai.assistance.operit.plugins.center.PluginCapabilitySpec
import com.ai.assistance.operit.plugins.center.CallerAwarePluginServiceEndpoint
import com.ai.assistance.operit.plugins.center.PluginInstallException
import com.ai.assistance.operit.plugins.center.PluginRuntimeAdapter
import com.ai.assistance.operit.plugins.center.PluginRuntimeAdapterContext
import com.ai.assistance.operit.plugins.center.PluginRuntimeHandle
import com.ai.assistance.operit.plugins.center.PluginRuntimeRole
import com.ai.limbs.plugin.runtime.InProcessCapabilityExecutor
import com.ai.limbs.plugin.runtime.InProcessNotificationAction
import com.ai.limbs.plugin.runtime.InProcessNotificationActionHandler
import com.ai.limbs.plugin.runtime.InProcessNotificationHost
import com.ai.limbs.plugin.runtime.InProcessNotificationState
import com.ai.limbs.plugin.runtime.InProcessProviderBinding
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import com.ai.limbs.plugin.runtime.ChildExtensionLifecycle
import java.io.File
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
internal object RemotePageProviderMetadata

internal class RemoteAndroidInProcessPluginRuntimeAdapter(
    private val notificationBindingProvider: (String, Set<String>) -> InProcessProviderBinding?
) : PluginRuntimeAdapter {
    override val kind: String = "android_inprocess"

    override suspend fun mount(context: PluginRuntimeAdapterContext): PluginRuntimeHandle {
        check(context.runtimeRole == PluginRuntimeRole.BUSINESS) {
            "Remote android_inprocess adapter is reserved for BUSINESS runtime"
        }
        val proxy = MountedRemotePlugin(context, notificationBindingProvider)
        proxy.mountAndRegister()
        return proxy
    }

    private class MountedRemotePlugin(
        private val context: PluginRuntimeAdapterContext,
        private val notificationBindingProvider: (String, Set<String>) -> InProcessProviderBinding?
    ) : PluginRuntimeHandle {
        private val pluginId = context.manifest.pluginId
        private val version = context.manifest.version
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mountMutex = Mutex()
        @Volatile private var sessionId: String? = null
        @Volatile private var closed = false
        private var providerRefreshJob: Job? = null
        private val uiProviders = linkedMapOf<String, RemoteUiStateProvider>()
        private val notificationState = MutableStateFlow<InProcessNotificationState?>(null)
        private var notificationHandle: AutoCloseable? = null

        suspend fun mountAndRegister() {
            val snapshot = ensureMounted(force = true)
            registerCapabilities(snapshot.optJSONArray("capabilities") ?: JSONArray())
            registerServices(snapshot.optJSONArray("services") ?: JSONArray())
            registerExtensions(snapshot.optJSONArray("extensions") ?: JSONArray())
            registerProviders(snapshot.optJSONArray("providers") ?: JSONArray())
            updateNotification(snapshot.optJSONObject("notification"))
            providerRefreshJob = scope.launch {
                while (isActive) {
                    delay(1_000L)
                    runCatching { refreshRemoteState() }
                }
            }
        }

        override suspend fun stop() = stopRemote(ownerShutdown = false)

        override suspend fun stopForOwnerShutdown() = stopRemote(ownerShutdown = true)

        private suspend fun stopRemote(ownerShutdown: Boolean) {
            closed = true
            providerRefreshJob?.cancel()
            runCatching { notificationHandle?.close() }
            notificationHandle = null
            notificationState.value = null
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

        private fun registerServices(descriptors: JSONArray) {
            for (index in 0 until descriptors.length()) {
                val envelope = ServiceContributionTransportCodec.decode(descriptors.getJSONObject(index))
                check(envelope.protocol == ServiceProxyProtocol.RPC)
                val contract = envelope.contract
                val id = contract.id
                val proxy = CallerAwarePluginServiceEndpoint { caller, operation, parameters ->
                    ensureMounted()
                    val sid = checkNotNull(sessionId)
                    val result = PluginRuntimeWire.request(
                        "service_invoke",
                        sid,
                        JSONObject()
                            .put("plugin_id", pluginId)
                            .put("service_id", id)
                            .put("caller_plugin_id", caller.pluginId)
                            .put("caller_roles", JSONArray(caller.roles.toList().sorted()))
                            .put("caller_scopes", JSONArray(caller.grantedScopes.toList().sorted()))
                            .put("service_operation", operation)
                            .put("parameters", JSONObject(parameters.toString())),
                        PluginRuntimeWire.BUSINESS_TIMEOUT_MS
                    )
                    result.getJSONObject("operation_result")
                }
                context.canonicalRestore.registerService(contract, proxy)
            }
        }

        private fun registerExtensions(descriptors: JSONArray) {
            for (index in 0 until descriptors.length()) {
                val envelope = ExtensionContributionTransportCodecRegistry.decode(descriptors.getJSONObject(index))
                context.canonicalRestore.registerExtension(envelope.contract, envelope.payload)
            }
        }

        private fun registerProviders(descriptors: JSONArray) {
            for (index in 0 until descriptors.length()) {
                val envelope = ProviderContributionTransportCodec.decode(descriptors.getJSONObject(index))
                val contract = envelope.contract
                val id = contract.id
                val payload: Any = when (envelope.protocol) {
                    ProviderProxyProtocol.CAPABILITY_EXECUTOR -> InProcessCapabilityExecutor { parametersJson ->
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
                    }
                    ProviderProxyProtocol.UI_STATE ->
                        RemoteUiStateProvider(id, envelope.proxy.optNullableString("state_json"))
                    ProviderProxyProtocol.PAGE_METADATA -> RemotePageProviderMetadata
                    ProviderProxyProtocol.CHILD_EXTENSION_INSTALLER -> object : ExtensionHubService {
                        override suspend fun install(
                            packageFile: File,
                            expectedParentPluginId: String?,
                            expectedPoint: String?
                        ): ChildExtensionSnapshot {
                            ensureMounted()
                            val sid = checkNotNull(sessionId)
                            val result = PluginRuntimeWire.request(
                                "provider_child_install",
                                sid,
                                JSONObject()
                                    .put("plugin_id", pluginId)
                                    .put("provider_id", id)
                                    .put("package_path", packageFile.absolutePath)
                                    .put("expected_parent_plugin_id", expectedParentPluginId ?: "")
                                    .put("expected_point", expectedPoint ?: ""),
                                PluginRuntimeWire.BUSINESS_TIMEOUT_MS
                            )
                            return parseChildSnapshot(result.getJSONObject("operation_result"))
                        }
                    }
                }

                context.canonicalRestore.registerProvider(contract, payload)
                if (payload is RemoteUiStateProvider) {
                    uiProviders[id] = payload
                }
            }
        }

        private suspend fun refreshRemoteState() {
            if (closed) return
            val snapshot = ensureMounted()
            val descriptors = snapshot.optJSONArray("providers") ?: JSONArray()
            for (index in 0 until descriptors.length()) {
                val envelope = ProviderContributionTransportCodec.decode(descriptors.getJSONObject(index))
                if (envelope.protocol == ProviderProxyProtocol.UI_STATE) {
                    uiProviders[envelope.contract.id]
                        ?.update(envelope.proxy.optNullableString("state_json"))
                }
            }
            updateNotification(snapshot.optJSONObject("notification"))
        }

        private fun updateNotification(snapshot: JSONObject?) {
            if (snapshot?.optBoolean("available", false) != true) {
                notificationState.value = null
                return
            }
            ensureNotificationRelay()
            val actions = buildList {
                val array = snapshot.optJSONArray("actions") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(InProcessNotificationAction(item.getString("id"), item.getString("label"), item.optInt("priority", 0), item.optBoolean("enabled", true)))
                }
            }
            notificationState.value = InProcessNotificationState(
                title = snapshot.getString("title"),
                summary = snapshot.optString("summary", ""),
                statusLines = snapshot.stringList("status_lines"),
                actions = actions
            )
        }

        private fun ensureNotificationRelay() {
            if (notificationHandle != null) return
            val binding = notificationBindingProvider(pluginId, context.payloadContext.permissions.grantedScopes) ?: return
            val host = binding.payload as? InProcessNotificationHost
                ?: throw PluginInstallException("PLUGIN_WORKER_NOTIFICATION_HOST_INVALID", "Core notification host has an incompatible payload")
            notificationHandle = host.publish(
                notificationState,
                InProcessNotificationActionHandler { actionId ->
                    ensureMounted()
                    val sid = checkNotNull(sessionId)
                    val result = PluginRuntimeWire.request(
                        "notification_action",
                        sid,
                        JSONObject().put("plugin_id", pluginId).put("action_id", actionId),
                        PluginRuntimeWire.BUSINESS_TIMEOUT_MS
                    )
                    check(result.getJSONObject("operation_result").optBoolean("accepted", false)) {
                        "Worker rejected notification action: $pluginId/$actionId"
                    }
                }
            )
        }

        private fun parseChildSnapshot(value: JSONObject): ChildExtensionSnapshot =
            ChildExtensionSnapshot(
                extensionId = value.getString("extension_id"),
                version = value.getString("version"),
                displayName = value.getString("display_name"),
                description = value.optNullableString("description"),
                target = ChildExtensionTarget(
                    parentPluginId = value.getString("parent_plugin_id"),
                    point = value.getString("point"),
                    apiVersion = value.getInt("api_version")
                ),
                lifecycle = ChildExtensionLifecycle.valueOf(value.getString("lifecycle").uppercase()),
                enabled = value.getBoolean("enabled"),
                roles = value.stringList("roles").toSet(),
                useCount = value.optLong("use_count", 0L),
                lastError = value.optNullableString("last_error")
            )

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
                refreshRemoteState()
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
