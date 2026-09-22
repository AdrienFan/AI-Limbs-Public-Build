package com.ai.assistance.operit.plugins.center

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardUITools
import com.ai.assistance.operit.services.FloatingChatService
import com.ai.assistance.operit.ui.common.displays.UIOperationOverlay
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.ui.permissions.PermissionRequestOverlay
import com.ai.assistance.operit.core.tools.system.resident.AiLimbsResidentRuntime
import com.ai.assistance.operit.core.tools.system.resident.ResidentComponentProxyBroker
import com.ai.assistance.operit.core.tools.system.resident.ResidentUiProxyWire
import com.ai.assistance.operit.plugins.center.isolation.ProviderContributionTransportCodec
import com.ai.assistance.operit.plugins.center.isolation.ProviderProxyProtocol
import com.ai.assistance.operit.plugins.system.KernelSelfMaintenanceJsonServiceV1
import com.ai.assistance.operit.plugins.system.KernelSystemPluginHostV2
import com.ai.assistance.operit.plugins.system.KernelSystemUiHostV1
import com.ai.assistance.operit.plugins.system.PluginPlatformControlV1
import com.ai.assistance.operit.plugins.system.SystemHostGatewayV1
import com.ai.assistance.operit.plugins.system.SystemHostPrimitiveAvailability
import com.ai.assistance.operit.plugins.system.SystemHostPrimitiveDescriptor
import com.ai.assistance.operit.plugins.system.SystemJsonServiceV1
import com.ai.assistance.operit.plugins.system.SystemPluginChildExtensionControlV2
import com.ai.assistance.operit.plugins.system.SystemPluginDelegatedCapabilityInvokerV2
import com.ai.assistance.operit.plugins.system.SystemPluginHostV2
import com.ai.assistance.operit.plugins.system.SystemPluginProviderBindingV2
import com.ai.assistance.operit.plugins.system.SystemPluginProviderDirectoryV2
import com.ai.assistance.operit.plugins.system.SystemPluginProtocolV1
import com.ai.assistance.operit.plugins.system.SystemPluginServiceEndpointV2
import com.ai.assistance.operit.plugins.system.SystemPluginServicePublisherV2
import com.ai.assistance.operit.plugins.system.SystemPluginController
import com.ai.limbs.plugin.runtime.ChildExtensionBackupSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionLifecycle
import com.ai.limbs.plugin.runtime.ChildExtensionSnapshot
import com.ai.limbs.plugin.runtime.ChildExtensionTarget
import com.ai.limbs.plugin.runtime.ChildUiContributionSnapshot
import com.ai.limbs.plugin.runtime.ExtensionHubService
import com.ai.limbs.plugin.runtime.InProcessNotificationAction
import com.ai.limbs.plugin.runtime.InProcessNotificationState
import com.ai.limbs.plugin.runtime.InProcessUiContributionProvider
import com.ai.limbs.plugin.runtime.InProcessUiStateProvider
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-side client for the Resident UI data/command plane.
 *
 * It owns only mirrored descriptors, local renderer objects and Android framework component work.
 * PluginManager, child runtime, capability executors and provider implementations remain in Core.
 */
internal class ResidentUiProxyClient(
    private val appContext: Context,
    private val runtime: PluginHostUiProxyRuntime
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val revision = AtomicLong(-1L)
    private val hostInstanceId = UUID.randomUUID().toString()
    private val hostGeneration = AtomicLong(0L)
    private val lastCoreRecoveryRequestElapsedMs = AtomicLong(0L)
    private val hostAttachLock = Any()
    private val lastSnapshot = AtomicReference(JSONObject())
    private val uiPayloadStager = ResidentUiPayloadStager(appContext)
    private val providerDirectory = ResidentProviderDirectory(this)
    private val childControl = ResidentChildControl(this)
    private val hostPrimitiveCache = AtomicReference<List<SystemHostPrimitiveDescriptor>>(emptyList())
    private val hostPrimitiveOperations = ConcurrentHashMap<String, List<String>>()
    @Volatile private var developerMode = false
    @Volatile private var developerDiscovery = false
    private val componentExecutor = ResidentHostComponentExecutor(appContext, this)
    private val childPresentationRuntime = ResidentChildPresentationRuntime(appContext, this, providerDirectory)
    private val presentationRuntime = ResidentPluginPresentationRuntime(appContext, this, providerDirectory)

    private lateinit var systemPluginController: SystemPluginController

    fun start() {
        if (!started.compareAndSet(false, true)) return
        systemPluginController = SystemPluginController(
            context = appContext,
            runtimeRole = PluginRuntimeRole.UI_PROXY,
            uiRegistry = runtime.systemUiRegistry,
            hostFactory = { pluginId, role -> createSystemHost(pluginId, role) }
        )
        systemPluginController.initialize()
        scope.launch {
            var rendererRestored = false
            while (isActive) {
                try {
                    refresh(force = revision.get() < 0L)
                    if (!rendererRestored) {
                        systemPluginController.restore()
                        rendererRestored = true
                    }
                    componentExecutor.pollAndExecute()
                    delay(POLL_MS)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    val terminalError = retryTransientTransport(error)
                    if (terminalError != null) {
                        failClosedDisconnected(terminalError)
                        delay(RETRY_MS)
                    }
                } catch (error: Throwable) {
                    failClosedDisconnected(error)
                    delay(RETRY_MS)
                }
            }
        }
    }

    suspend fun installPluginCenterRendererFromUri(
        uriText: String,
        originalName: String
    ): com.ai.assistance.operit.plugins.system.SystemPluginValidationResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            systemPluginController.installFirstTrustedFromUri(uriText, originalName)
        }

    fun updateAttachment() {
        // Session identity is checked by PluginHostUiProxyRuntime before this call. A refreshed attach
        // needs no new business runtime; the poll loop simply continues against the same Core session.
    }

    suspend fun command(payload: JSONObject): JSONObject =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                wireRequest("command", stageResidentCommandPayload(payload))
            } catch (error: IOException) {
                transportFailureResult(error)
            }
        }

    suspend fun stageUiPayload(payloadJson: String): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            uiPayloadStager.stagePickerPayload(payloadJson)
        }

    fun commandBlocking(payload: JSONObject): JSONObject =
        try {
            wireRequest("command", stageResidentCommandPayload(payload))
        } catch (error: IOException) {
            transportFailureResult(error)
        }

    /**
     * Final Host-side guard before a UI command crosses into app_process-owned Resident code.
     *
     * Picker payloads are JSON-encoded inside payload_json, so recursively staging the outer
     * command object is not enough. Materialize them here even if an upper UI proxy forgot to
     * call stageUiPayload(). Resident must never dereference Android ContentProvider URIs.
     */
    private fun stageResidentCommandPayload(payload: JSONObject): JSONObject {
        val staged = JSONObject(payload.toString())
        when (staged.optString("command")) {
            "ui_provider_event", "child_ui_event" -> {
                val payloadJson = staged.optString("payload_json")
                if (payloadJson.isNotBlank()) {
                    staged.put("payload_json", uiPayloadStager.stagePickerPayload(payloadJson))
                }
            }
            "invoke_ui_capability" -> {
                staged.optJSONObject("parameters")?.let { parameters ->
                    staged.put(
                        "parameters",
                        JSONObject(uiPayloadStager.stagePickerPayload(parameters.toString()))
                    )
                }
            }
            "system_json" -> {
                val service = staged.optString("service").trim().lowercase()
                val operation = staged.optString("operation").trim().lowercase()
                if (service == "plugin_admin" && operation in PLUGIN_ADMIN_URI_OPERATIONS) {
                    staged.optJSONObject("parameters")?.let { parameters ->
                        val uriText = parameters.optString("uri").trim()
                        if (uriText.startsWith("content://")) {
                            parameters.put("uri", uiPayloadStager.stagePluginPackageUri(uriText))
                        }
                    }
                }
            }
        }
        return staged
    }

    suspend fun invokeUiCapability(
        ownerPluginId: String,
        screenId: String,
        capabilityId: String,
        parameters: JSONObject
    ): JSONObject {
        val stagedParameters = JSONObject(stageUiPayload(parameters.toString()))
        return command(
            JSONObject()
                .put("command", "invoke_ui_capability")
                .put("owner_plugin_id", ownerPluginId)
                .put("screen_id", screenId)
                .put("capability_id", capabilityId)
                .put("parameters", stagedParameters)
        )
    }

    fun setActiveScreen(screenId: String?) {
        runtime.pagePresentationRegistry.setActiveScreen(screenId)
        scope.launch {
            runCatching {
                command(JSONObject().put("command", "set_active_screen").put("screen_id", screenId ?: ""))
            }.onFailure { com.ai.assistance.operit.util.AppLogger.w(TAG, "Core active-screen update failed", it) }
        }
    }

    fun setPresentationMode(ownerPluginId: String, screenId: String, mode: PluginPagePresentationMode) {
        runtime.pagePresentationRegistry.set(ownerPluginId, screenId, mode)
        scope.launch {
            runCatching {
                command(
                    JSONObject().put("command", "set_presentation_mode")
                        .put("owner_plugin_id", ownerPluginId)
                        .put("screen_id", screenId)
                        .put("mode", mode.name.lowercase())
                )
            }.onFailure { com.ai.assistance.operit.util.AppLogger.w(TAG, "Core presentation update failed", it) }
        }
    }

    fun dispatchNotificationAction(bindingId: String, actionId: String) {
        scope.launch {
            runCatching {
                command(
                    JSONObject().put("command", "notification_action")
                        .put("binding_id", bindingId)
                        .put("action_id", actionId)
                )
            }.onFailure {
                com.ai.assistance.operit.util.AppLogger.w(TAG, "Core notification action failed: $actionId", it)
            }
        }
    }

    fun providers(): SystemPluginProviderDirectoryV2 = providerDirectory
    fun children(): SystemPluginChildExtensionControlV2 = childControl
    fun hostPrimitives(): List<SystemHostPrimitiveDescriptor> = hostPrimitiveCache.get()
    fun primitiveOperations(id: String): List<String> = hostPrimitiveOperations[id.trim().lowercase()].orEmpty()
    fun developerModeEnabled(): Boolean = developerMode
    fun developerDiscoveryEnabled(): Boolean = developerDiscovery

    private fun sessionId(): String = checkNotNull(runtime.attachment.coreSessionId) {
        "UI proxy attachment has no Resident Core session"
    }

    private fun ensureHostGeneration(): Long = synchronized(hostAttachLock) {
        val current = hostGeneration.get()
        if (current > 0L) return@synchronized current
        val attached = ResidentUiProxyWire.request(
            operation = "attach",
            sessionId = sessionId(),
            hostInstanceId = hostInstanceId,
            hostGeneration = -1L
        )
        val generation = attached.getLong("host_generation")
        check(generation > 0L) { "Resident UI proxy returned invalid Host generation" }
        hostGeneration.set(generation)
        generation
    }

    private fun wireRequest(
        operation: String,
        payload: JSONObject = JSONObject(),
        requestRecoveryOnFailure: Boolean = true
    ): JSONObject {
        return try {
            val generation = ensureHostGeneration()
            ResidentUiProxyWire.request(
                operation = operation,
                sessionId = sessionId(),
                hostInstanceId = hostInstanceId,
                hostGeneration = generation,
                payload = JSONObject(payload.toString())
            ).also { lastCoreRecoveryRequestElapsedMs.set(0L) }
        } catch (error: IOException) {
            hostGeneration.set(0L)
            if (requestRecoveryOnFailure) {
                requestCoreRecoveryAfterTransportFailure(error)
            }
            throw error
        }
    }

    private fun requestCoreRecoveryAfterTransportFailure(error: IOException) {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastCoreRecoveryRequestElapsedMs.get()
            if (
                previous > 0L &&
                nowElapsed - previous < CORE_RECOVERY_REQUEST_COOLDOWN_MS
            ) {
                return
            }
            if (lastCoreRecoveryRequestElapsedMs.compareAndSet(previous, nowElapsed)) {
                com.ai.assistance.operit.util.AppLogger.w(
                    TAG,
                    "Resident UI proxy transport failed; requesting Core recovery: " +
                        "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                    error
                )
                AiLimbsResidentRuntime.scheduleCoreCrashRecovery(appContext)
                return
            }
        }
    }

    private fun transportFailureResult(error: IOException): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("error_code", "RESIDENT_CORE_UNAVAILABLE")
            .put("error", "Resident Core connection is unavailable; recovery has been requested")
            .put("recovering", AiLimbsResidentRuntime.isEnabledForHost())
            .put("transport_error", error.javaClass.simpleName)

    private suspend fun refresh(force: Boolean) {
        val result = if (force) {
            wireRequest("snapshot", requestRecoveryOnFailure = false)
        } else {
            wireRequest(
                "events",
                JSONObject().put("since_revision", revision.get()),
                requestRecoveryOnFailure = false
            )
        }
        val nextRevision = result.optLong("revision", revision.get())
        revision.set(nextRevision)
        if (!result.optBoolean("changed", false) || result.isNull("snapshot")) return
        val snapshot = result.getJSONObject("snapshot")
        lastSnapshot.set(JSONObject(snapshot.toString()))
        applySnapshot(snapshot)
    }

    private suspend fun retryTransientTransport(initialError: IOException): Throwable? {
        var lastError: Throwable = initialError
        com.ai.assistance.operit.util.AppLogger.w(
            TAG,
            "Resident UI proxy transport transient failure; preserving last trusted snapshot while retrying: " +
                "${initialError.javaClass.simpleName}: ${initialError.message.orEmpty()}"
        )
        repeat(TRANSIENT_TRANSPORT_RETRY_COUNT) { attempt ->
            delay(TRANSIENT_TRANSPORT_RETRY_DELAY_MS)
            try {
                refresh(force = true)
                com.ai.assistance.operit.util.AppLogger.i(
                    TAG,
                    "Resident UI proxy transport recovered on retry ${attempt + 1}; preserved last trusted snapshot"
                )
                return null
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastError = error
            } catch (error: Throwable) {
                return error
            }
        }
        val transportError = lastError as? IOException
        if (transportError != null) {
            requestCoreRecoveryAfterTransportFailure(transportError)
        }
        com.ai.assistance.operit.util.AppLogger.w(
            TAG,
            "Resident UI proxy transport unavailable after $TRANSIENT_TRANSPORT_RETRY_COUNT retries; failing closed",
            lastError
        )
        return lastError
    }

    private suspend fun failClosedDisconnected(error: Throwable) {
        revision.set(-1L)
        hostGeneration.set(0L)
        lastSnapshot.set(JSONObject())

        // Visibility is revoked before any plugin cleanup runs. Disconnect cleanup is deliberately
        // idempotent because every retry may fail independently.
        runtime.replaceForegroundNotification(null)
        runtime.uiRegistry.replaceFromResidentProxy(emptyList(), emptyList(), null)
        runtime.pagePresentationRegistry.replaceFromResidentProxy(emptyList())
        runtime.dynamicNavigationRegistry.replaceFromResidentProxy(emptyList(), emptyList())
        childControl.update(JSONArray(), JSONArray(), JSONArray())
        providerDirectory.disconnectFailClosed()

        developerMode = false
        developerDiscovery = false
        hostPrimitiveCache.set(emptyList())
        hostPrimitiveOperations.clear()

        // Visibility is already revoked. Parent and child presentation cleanup is independent and
        // both runtimes forcibly forget Host ownership even if plugin stop hooks fail or time out.
        runCatching { presentationRuntime.disconnectFailClosed() }
            .onFailure {
                com.ai.assistance.operit.util.AppLogger.w(
                    TAG,
                    "Parent presentation fail-closed cleanup reported an error",
                    it
                )
            }
        runCatching { childPresentationRuntime.disconnectFailClosed() }
            .onFailure {
                com.ai.assistance.operit.util.AppLogger.w(
                    TAG,
                    "Child presentation fail-closed cleanup reported an error",
                    it
                )
            }

        // A presentation stop hook is untrusted code and may try to republish during teardown.
        // Close the directory again so disconnected always means unavailable.
        providerDirectory.disconnectFailClosed()

        com.ai.assistance.operit.util.AppLogger.w(
            TAG,
            "Resident UI proxy disconnected; Core-owned presentation state is unavailable",
            error
        )
    }

    private suspend fun applySnapshot(snapshot: JSONObject) {
        val tiles = snapshot.getJSONArray("tiles").objects().map { item ->
            PluginHomeTileSpec(
                ownerPluginId = item.getString("owner_plugin_id"), id = item.getString("id"),
                title = item.getString("title"), description = item.optString("description", ""),
                screenId = item.getString("screen_id")
            )
        }
        val screens = snapshot.getJSONArray("screens").objects().map { item ->
            PluginScreenSpec(
                ownerPluginId = item.getString("owner_plugin_id"), id = item.getString("id"), title = item.getString("title"),
                description = item.stringOrNull("description"), schemaId = item.getString("schema_id"),
                documentJson = item.getString("document_json")
            )
        }
        val theme = snapshot.optJSONObject("theme")?.let { item ->
            PluginThemeSpec(
                ownerPluginId = item.getString("owner_plugin_id"), id = item.getString("id"),
                mode = PluginThemeMode.valueOf(item.getString("mode").uppercase()), pureBlack = item.optBoolean("pure_black", false),
                colors = item.optJSONObject("colors")?.stringMap().orEmpty(),
                backgroundGradient = item.optJSONArray("background_gradient")?.strings().orEmpty()
            )
        }
        runtime.uiRegistry.replaceFromResidentProxy(tiles, screens, theme)

        val presentations = snapshot.getJSONArray("presentations").objects().map { item ->
            PluginPagePresentationRequest(
                ownerPluginId = item.getString("owner_plugin_id"), screenId = item.getString("screen_id"),
                mode = PluginPagePresentationMode.parse(item.getString("mode"))
            )
        }
        runtime.pagePresentationRegistry.replaceFromResidentProxy(presentations)

        val notification =
            if (snapshot.isNull("notification")) {
                null
            } else {
                snapshot.getJSONObject("notification").let { item ->
                    val state = item.getJSONObject("state")
                    PluginForegroundNotificationSnapshot(
                        bindingId = item.getString("binding_id"),
                        ownerPluginId = item.getString("owner_plugin_id"),
                        state = InProcessNotificationState(
                            title = state.getString("title"),
                            summary = state.optString("summary", ""),
                            statusLines = state.optJSONArray("status_lines")?.strings().orEmpty(),
                            actions = state.optJSONArray("actions")?.objects().orEmpty().map { action ->
                                InProcessNotificationAction(
                                    id = action.getString("id"),
                                    label = action.getString("label"),
                                    priority = action.optInt("priority", 0),
                                    enabled = action.optBoolean("enabled", true)
                                )
                            }
                        )
                    )
                }
            }
        runtime.replaceForegroundNotification(notification)

        val navSurfaces = snapshot.getJSONArray("navigation_surfaces").objects().map { item ->
            DynamicNavigationSurfaceSpec(
                id = item.getString("id"), title = item.getString("title"), iconKey = item.getString("icon_key"),
                order = item.getInt("order"), createdAt = item.getLong("created_at")
            )
        }
        val navBindings = snapshot.getJSONArray("navigation_bindings").objects().map { item ->
            DynamicNavigationBinding(
                surfaceId = item.getString("surface_id"), ownerPluginId = item.getString("owner_plugin_id"),
                tileId = item.getString("tile_id"), screenId = item.getString("screen_id")
            )
        }
        runtime.dynamicNavigationRegistry.replaceFromResidentProxy(navSurfaces, navBindings)

        providerDirectory.update(snapshot.getJSONArray("providers"))
        childControl.update(
            snapshot.getJSONArray("children"), snapshot.getJSONArray("child_backups"),
            snapshot.getJSONArray("child_ui_contributions")
        )
        // Child presentation providers must exist before parent presentation entries reconcile so
        // a parent such as System Environment Center can observe its Host-local Ubuntu display.
        childPresentationRuntime.reconcile(snapshot.optJSONArray("child_presentations") ?: JSONArray())
        presentationRuntime.reconcile(snapshot.optJSONArray("plugin_presentations") ?: JSONArray())
        developerMode = snapshot.optBoolean("developer_mode", false)
        developerDiscovery = snapshot.optBoolean("developer_discovery_enabled", false)
        val primitives = mutableListOf<SystemHostPrimitiveDescriptor>()
        hostPrimitiveOperations.clear()
        snapshot.getJSONArray("host_primitives").objects().forEach { item ->
            val descriptor = SystemHostPrimitiveDescriptor(
                number = item.getInt("number"), id = item.getString("id"), title = item.getString("title"),
                description = item.getString("description"), boundary = item.getString("boundary"),
                maturity = item.getString("maturity"), exposure = item.getString("exposure"),
                requestableScope = item.getBoolean("requestable_scope"),
                policyAllowed = if (item.isNull("policy_allowed")) null else item.getBoolean("policy_allowed"),
                callable = item.getBoolean("callable")
            )
            primitives += descriptor
            hostPrimitiveOperations[descriptor.id.lowercase()] = item.optJSONArray("operations")?.strings().orEmpty()
        }
        hostPrimitiveCache.set(primitives)
    }

    private fun createSystemHost(pluginId: String, role: String): SystemPluginHostV2 {
        check(role.trim().lowercase() == SystemPluginProtocolV1.ROLE_PLUGIN_CENTER)
        val ui = KernelSystemUiHostV1(pluginId, role, runtime.systemUiRegistry)
        return KernelSystemPluginHostV2(
            SystemPluginProtocolV1.HOST_ABI,
            ResidentSystemHostGateway(appContext, this),
            ResidentPluginPlatformControl(this),
            ResidentSystemJsonService(this, "plugin_admin"),
            ResidentSystemJsonService(this, "admin_security"),
            KernelSelfMaintenanceJsonServiceV1(systemPluginController),
            ResidentSystemJsonService(this, "navigation"),
            ui,
            ResidentPresentationLocalServicePublisher,
            ResidentDelegatedCapabilityInvoker(this),
            providerDirectory,
            childControl
        )
    }

    internal fun componentRequest(operation: String, payload: JSONObject): JSONObject =
        wireRequest(operation, payload)

    internal fun completeComponentAsync(requestId: String, result: JSONObject) {
        scope.launch {
            runCatching {
                componentRequest(
                    "component_result",
                    JSONObject().put("request_id", requestId).put("result", JSONObject(result.toString()))
                )
            }.onFailure {
                com.ai.assistance.operit.util.AppLogger.w(TAG, "Host component result delivery failed: $requestId", it)
            }
        }
    }

    companion object {
        private const val TAG = "ResidentUiProxyClient"
        private const val POLL_MS = 350L
        private const val RETRY_MS = 750L
        private const val TRANSIENT_TRANSPORT_RETRY_COUNT = 3
        private const val TRANSIENT_TRANSPORT_RETRY_DELAY_MS = 250L
        private const val CORE_RECOVERY_REQUEST_COOLDOWN_MS = 5_000L
        private val PLUGIN_ADMIN_URI_OPERATIONS = setOf("inspect_uri", "install_uri")
    }
}

private class ResidentSystemJsonService(
    private val client: ResidentUiProxyClient,
    private val service: String
) : SystemJsonServiceV1 {
    override suspend fun call(operation: String, parameters: JSONObject): JSONObject = client.command(
        JSONObject().put("command", "system_json").put("service", service).put("operation", operation)
            .put("parameters", JSONObject(parameters.toString()))
    )
}

private class ResidentDelegatedCapabilityInvoker(
    private val client: ResidentUiProxyClient
) : SystemPluginDelegatedCapabilityInvokerV2 {
    override suspend fun invokeAsActivePlugin(pluginId: String, capabilityId: String, parameters: JSONObject): JSONObject =
        client.command(JSONObject().put("command", "delegated_capability").put("plugin_id", pluginId)
            .put("capability_id", capabilityId).put("parameters", JSONObject(parameters.toString())))
}

private class ResidentPluginPlatformControl(
    private val client: ResidentUiProxyClient
) : PluginPlatformControlV1 {
    override fun developerModeEnabled(): Boolean = client.developerModeEnabled()
    override fun developerDiscoveryEnabled(): Boolean = client.developerDiscoveryEnabled()
    override fun hostPrimitiveSnapshots(): List<SystemHostPrimitiveDescriptor> = client.hostPrimitives()
    override suspend fun setDeveloperMode(enabled: Boolean) {
        client.command(JSONObject().put("command", "plugin_platform").put("operation", "set_developer_mode").put("enabled", enabled))
    }
    override suspend fun setDeveloperDiscoveryEnabled(enabled: Boolean) {
        client.command(JSONObject().put("command", "plugin_platform").put("operation", "set_developer_discovery").put("enabled", enabled))
    }
    override suspend fun setHostPrimitiveAllowed(primitiveId: String, allowed: Boolean) {
        client.command(JSONObject().put("command", "plugin_platform").put("operation", "set_host_primitive_allowed")
            .put("primitive_id", primitiveId).put("allowed", allowed))
    }
}

private class ResidentSystemHostGateway(
    private val appContext: Context,
    private val client: ResidentUiProxyClient
) : SystemHostGatewayV1 {
    override fun listHostPrimitives(): List<SystemHostPrimitiveDescriptor> = client.hostPrimitives()
    override fun describeHostPrimitive(id: String): SystemHostPrimitiveDescriptor? = client.hostPrimitives().firstOrNull { it.id == id.trim().lowercase() }
    override fun listHostPrimitiveOperations(id: String): List<String> = client.primitiveOperations(id)
    override fun availabilityHostPrimitive(id: String, operation: String?): SystemHostPrimitiveAvailability {
        val descriptor = describeHostPrimitive(id)
            ?: return SystemHostPrimitiveAvailability(id, operation, false, false, false, "HOST_PRIMITIVE_UNKNOWN", "Unknown Host Primitive")
        val operationKnown = operation == null || operation.trim().lowercase() in client.primitiveOperations(descriptor.id)
        return SystemHostPrimitiveAvailability(
            descriptor.id, operation, true, descriptor.callable, descriptor.callable && operationKnown,
            if (operationKnown) null else "HOST_OPERATION_UNKNOWN", if (operationKnown) null else "Unknown Host Primitive operation"
        )
    }
    override suspend fun invokeHostPrimitive(id: String, parameters: JSONObject): JSONObject {
        val normalized = id.trim().lowercase()
        check(normalized != RESIDENT_RUNTIME_ID) {
            "Resident runtime requires an explicit lifecycle operation"
        }
        return client.command(
            JSONObject().put("command", "host_primitive").put("id", normalized)
                .put("parameters", JSONObject(parameters.toString()))
        )
    }

    override suspend fun invokeHostPrimitive(id: String, operation: String, parameters: JSONObject): JSONObject {
        val normalized = id.trim().lowercase()
        if (normalized == RESIDENT_RUNTIME_ID) {
            // Resident lifecycle is an Android-Host control plane. Routing this back into BUSINESS
            // Core would make the managed process responsible for stopping/replacing itself.
            return com.ai.assistance.operit.core.tools.system.resident.AiLimbsResidentRuntime.invoke(
                appContext,
                com.ai.limbs.plugin.runtime.InProcessSystemIds.PLUGIN_CENTER_PLUGIN_ID,
                operation,
                JSONObject(parameters.toString())
            )
        }
        return client.command(
            JSONObject().put("command", "host_primitive").put("id", normalized)
                .put("host_operation", operation).put("parameters", JSONObject(parameters.toString()))
        )
    }

    private companion object {
        const val RESIDENT_RUNTIME_ID = "host.resident.runtime@1"
    }
}

/**
 * Plugin Center may publish presentation helper services while its UI package mounts in Host.
 * They are intentionally not exported to Core and therefore cannot become a second business owner.
 */
private object ResidentPresentationLocalServicePublisher : SystemPluginServicePublisherV2 {
    override fun publish(
        id: String, apiVersion: Int, endpoint: SystemPluginServiceEndpointV2, metadata: Map<String, String>
    ): AutoCloseable = AutoCloseable { }
}

internal class ResidentProviderDirectory(
    private val client: ResidentUiProxyClient
) : SystemPluginProviderDirectoryV2 {
    private data class LocalOwned(val token: String, val binding: SystemPluginProviderBindingV2)
    private data class RemoteMetadata(val ownerPluginId: String, val metadata: Map<String, String>)

    private val state = MutableStateFlow<Map<String, SystemPluginProviderBindingV2>>(emptyMap())
    private val remote = AtomicReference<Map<String, SystemPluginProviderBindingV2>>(emptyMap())
    private val pageMetadata = AtomicReference<Map<String, RemoteMetadata>>(emptyMap())
    private val localPages = ConcurrentHashMap<String, LocalOwned>()
    private val proxies = ConcurrentHashMap<String, ResidentUiStateProviderProxy>()
    private val childInstallerProxies = ConcurrentHashMap<String, ResidentChildExtensionInstallerProxy>()

    fun update(array: JSONArray) {
        val next = linkedMapOf<String, SystemPluginProviderBindingV2>()
        val nextPageMetadata = linkedMapOf<String, RemoteMetadata>()
        array.objects().forEach { item ->
            val envelope = ProviderContributionTransportCodec.decode(item)
            val contract = envelope.contract
            val id = contract.id
            val owner = contract.ownerPluginId
            val metadata = contract.metadata
            when (envelope.protocol) {
                ProviderProxyProtocol.UI_STATE -> {
                    val proxy = proxies.compute(id) { _, old ->
                        if (old != null && old.ownerPluginId == owner) old else ResidentUiStateProviderProxy(client, owner, id)
                    }!!
                    proxy.update(envelope.proxy.stringOrNull("state_json"))
                    next[id] = SystemPluginProviderBindingV2(owner, id, metadata, proxy)
                }
                ProviderProxyProtocol.CHILD_EXTENSION_INSTALLER -> {
                    val proxy = childInstallerProxies.compute(id) { _, old ->
                        if (old != null && old.ownerPluginId == owner) old else ResidentChildExtensionInstallerProxy(client, owner, id)
                    }!!
                    next[id] = SystemPluginProviderBindingV2(owner, id, metadata, proxy)
                }
                ProviderProxyProtocol.PAGE_METADATA ->
                    nextPageMetadata[id] = RemoteMetadata(owner, metadata)
                ProviderProxyProtocol.CAPABILITY_EXECUTOR -> Unit // Not part of Host presentation state.
            }
        }
        proxies.keys.retainAll(next.keys)
        childInstallerProxies.keys.retainAll(next.keys)
        remote.set(next)
        pageMetadata.set(nextPageMetadata)
        publish()
    }

    fun disconnectFailClosed() {
        proxies.values.forEach { proxy -> runCatching { proxy.update(null) } }
        proxies.clear()
        childInstallerProxies.clear()
        remote.set(emptyMap())
        pageMetadata.set(emptyMap())
        localPages.clear()
        state.value = emptyMap()
    }

    fun registerLocalPageProvider(
        ownerPluginId: String,
        id: String,
        provider: com.ai.limbs.plugin.runtime.InProcessPageProvider,
        metadata: Map<String, String>
    ): AutoCloseable = registerLocalPresentationProvider(ownerPluginId, id, provider, metadata)

    fun registerLocalPresentationProvider(
        ownerId: String,
        id: String,
        payload: Any,
        metadata: Map<String, String>
    ): AutoCloseable {
        val normalized = id.trim()
        require(ownerId.isNotBlank() && normalized.isNotEmpty())
        check(normalized !in remote.get()) { "Host-local presentation provider conflicts with Core provider: $normalized" }
        val token = UUID.randomUUID().toString()
        val candidate = LocalOwned(token, SystemPluginProviderBindingV2(ownerId, normalized, metadata.toMap(), payload))
        check(localPages.putIfAbsent(normalized, candidate) == null) { "Host-local presentation provider already registered: $normalized" }
        publish()
        return AutoCloseable {
            var removed = false
            localPages.computeIfPresent(normalized) { _, current ->
                if (current.token == token) { removed = true; null } else current
            }
            if (removed) publish()
        }
    }

    private fun publish() {
        val merged = linkedMapOf<String, SystemPluginProviderBindingV2>()
        remote.get().toSortedMap().forEach { (id, binding) -> merged[id] = binding }
        localPages.toSortedMap().forEach { (id, owned) ->
            check(id !in merged) { "Host-local presentation provider conflicts with Core provider: $id" }
            val overlay = pageMetadata.get()[id]
            if (overlay != null) {
                check(overlay.ownerPluginId == owned.binding.ownerPluginId) {
                    "Host-local presentation provider owner conflicts with Core metadata: $id"
                }
            }
            merged[id] = SystemPluginProviderBindingV2(
                owned.binding.ownerPluginId,
                owned.binding.id,
                overlay?.metadata.orEmpty() + owned.binding.metadata,
                owned.binding.payload
            )
        }
        state.value = merged
    }

    override fun resolve(id: String): SystemPluginProviderBindingV2? = state.value[id.trim()]
    override fun snapshot(): List<SystemPluginProviderBindingV2> = state.value.values.sortedBy { it.id }
    override fun observe(id: String): Flow<SystemPluginProviderBindingV2?> = state.map { it[id.trim()] }
}

private class ResidentChildExtensionInstallerProxy(
    private val client: ResidentUiProxyClient,
    val ownerPluginId: String,
    private val providerId: String
) : ExtensionHubService {
    override suspend fun install(
        packageFile: java.io.File,
        expectedParentPluginId: String?,
        expectedPoint: String?
    ): ChildExtensionSnapshot {
        check(packageFile.isFile) { "Selected child extension package is unavailable: ${packageFile.path}" }
        val result = client.command(
            JSONObject()
                .put("command", "provider_child_install")
                .put("owner_plugin_id", ownerPluginId)
                .put("provider_id", providerId)
                .put("package_path", packageFile.absolutePath)
                .put("expected_parent_plugin_id", expectedParentPluginId ?: "")
                .put("expected_point", expectedPoint ?: "")
        )
        return childSnapshot(result)
    }
}

private class ResidentUiStateProviderProxy(
    private val client: ResidentUiProxyClient,
    val ownerPluginId: String,
    private val providerId: String
) : InProcessUiStateProvider {
    private val mutableState = MutableStateFlow<String?>(null)
    override val stateJson: StateFlow<String?> = mutableState.asStateFlow()
    fun update(value: String?) { mutableState.value = value }
    override suspend fun perform(eventId: String, payloadJson: String): String {
        val stagedPayload = client.stageUiPayload(payloadJson)
        return client.command(JSONObject().put("command", "ui_provider_event").put("owner_plugin_id", ownerPluginId)
            .put("provider_id", providerId).put("event_id", eventId).put("payload_json", stagedPayload))
            .getString("result_json")
    }
}

private class ResidentChildContributionProxy(
    private val client: ResidentUiProxyClient,
    private val extensionId: String,
    private val contributionId: String
) : InProcessUiContributionProvider {
    private val mutableDocument = MutableStateFlow<String?>(null)
    override val documentJson: StateFlow<String?> = mutableDocument.asStateFlow()
    fun update(value: String?) { mutableDocument.value = value }
    override suspend fun perform(eventId: String, payloadJson: String): String {
        val stagedPayload = client.stageUiPayload(payloadJson)
        return client.command(JSONObject().put("command", "child_ui_event").put("extension_id", extensionId)
            .put("contribution_id", contributionId).put("event_id", eventId).put("payload_json", stagedPayload))
            .getString("result_json")
    }
}

private class ResidentChildControl(
    private val client: ResidentUiProxyClient
) : SystemPluginChildExtensionControlV2 {
    private val mutableSnapshots = MutableStateFlow<List<ChildExtensionSnapshot>>(emptyList())
    private val mutableBackups = MutableStateFlow<List<ChildExtensionBackupSnapshot>>(emptyList())
    private val mutableUi = MutableStateFlow<List<ChildUiContributionSnapshot>>(emptyList())
    private val pointFlows = ConcurrentHashMap<String, MutableStateFlow<List<ChildExtensionSnapshot>>>()
    private val contributionProxies = ConcurrentHashMap<String, ResidentChildContributionProxy>()

    fun update(snapshots: JSONArray, backups: JSONArray, ui: JSONArray) {
        val nextSnapshots = snapshots.objects().map(::childSnapshot)
        mutableSnapshots.value = nextSnapshots
        pointFlows.forEach { (point, flow) -> flow.value = nextSnapshots.filter { it.target.point == point } }
        mutableBackups.value = backups.objects().map(::childBackup)
        val activeKeys = linkedSetOf<String>()
        mutableUi.value = ui.objects().map { item ->
            val extensionId = item.getString("extension_id")
            val contributionId = item.getString("contribution_id")
            val key = "$extensionId|$contributionId"
            activeKeys += key
            val provider = contributionProxies.computeIfAbsent(key) { ResidentChildContributionProxy(client, extensionId, contributionId) }
            provider.update(item.stringOrNull("document_json"))
            ChildUiContributionSnapshot(
                extensionId = extensionId,
                target = ChildExtensionTarget(item.getString("parent_plugin_id"), item.getString("point"), item.getInt("api_version")),
                screenId = item.getString("screen_id"), componentId = item.getString("component_id"),
                slotId = item.getString("slot_id"), contributionId = contributionId, provider = provider
            )
        }
        contributionProxies.keys.retainAll(activeKeys)
    }

    override suspend fun uninstall(extensionId: String): Boolean = childCommand("uninstall", extensionId).getBoolean("removed")
    override suspend fun setEnabled(extensionId: String, enabled: Boolean): ChildExtensionSnapshot =
        childSnapshot(childCommand("set_enabled", extensionId, JSONObject().put("enabled", enabled)))
    override suspend fun backup(extensionId: String): ChildExtensionBackupSnapshot = childBackup(childCommand("backup", extensionId))
    override suspend fun restoreBackup(extensionId: String): ChildExtensionSnapshot = childSnapshot(childCommand("restore_backup", extensionId))
    override suspend fun deleteBackup(extensionId: String): Boolean = childCommand("delete_backup", extensionId).getBoolean("deleted")
    override fun versions(extensionId: String): List<String> = mutableSnapshots.value.firstOrNull { it.extensionId == extensionId }?.let { listOf(it.version) }.orEmpty()
    override fun retentionLimit(extensionId: String): Int = 3
    override fun immediateRollbackVersion(extensionId: String): String? = null
    override suspend fun activateVersion(extensionId: String, version: String): ChildExtensionSnapshot = childSnapshot(childCommand("activate_version", extensionId, JSONObject().put("version", version)))
    override suspend fun immediateRollback(extensionId: String): ChildExtensionSnapshot = childSnapshot(childCommand("immediate_rollback", extensionId))
    override suspend fun deleteVersion(extensionId: String, version: String): Boolean = childCommand("delete_version", extensionId, JSONObject().put("version", version)).getBoolean("deleted")
    override suspend fun setVersionRetention(extensionId: String, limit: Int) { childCommand("set_version_retention", extensionId, JSONObject().put("limit", limit)) }
    override suspend fun setAutoBackupPolicy(enabled: Boolean, highFrequencyUseCount: Long) {
        childCommand("set_auto_backup_policy", "", JSONObject().put("enabled", enabled).put("high_frequency_use_count", highFrequencyUseCount))
    }
    override suspend fun exportBackups(extensionIds: Collection<String>, treeUri: String): List<String> {
        val result = childCommand("export_backups", "", JSONObject().put("extension_ids", JSONArray(extensionIds)).put("tree_uri", treeUri))
        return result.getJSONArray("exported").strings()
    }
    override fun snapshots(): StateFlow<List<ChildExtensionSnapshot>> = mutableSnapshots.asStateFlow()
    override fun snapshotsForPoint(point: String): StateFlow<List<ChildExtensionSnapshot>> =
        pointFlows.computeIfAbsent(point) { MutableStateFlow(mutableSnapshots.value.filter { it.target.point == point }) }.asStateFlow()
    override fun backupSnapshots(): StateFlow<List<ChildExtensionBackupSnapshot>> = mutableBackups.asStateFlow()
    override fun uiContributions(): StateFlow<List<ChildUiContributionSnapshot>> = mutableUi.asStateFlow()

    private suspend fun childCommand(operation: String, id: String, extras: JSONObject = JSONObject()): JSONObject {
        val request = JSONObject(extras.toString()).put("command", "child_control").put("operation", operation)
        if (id.isNotBlank()) request.put("extension_id", id)
        return client.command(request)
    }
}

/** Executes only Android framework work in Host. Raw framework objects never enter the wire. */
private class ResidentHostComponentExecutor(
    private val appContext: Context,
    private val client: ResidentUiProxyClient
) {
    private val windowLeases = ConcurrentHashMap<String, WeakReference<android.view.Window>>()
    private val permissionOverlay = PermissionRequestOverlay(appContext)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hostPrimitiveAdapter = KernelHostPrimitiveAdapter(appContext, PluginRuntimeRole.UI_PROXY)
    private val hostScreenCaptureTools = StandardUITools(appContext)

    suspend fun pollAndExecute() {
        val result = client.componentRequest("component_poll", JSONObject().put("max_items", 8))
        result.optJSONArray("requests")?.objects().orEmpty().forEach { request ->
            val id = request.getString("request_id")
            val outcome: JSONObject? = try {
                execute(
                    id,
                    request.getString("kind"),
                    request.optJSONObject("payload") ?: JSONObject(),
                    request.optLong("deadline_elapsed_ms", 0L)
                )
            } catch (error: Throwable) {
                JSONObject().put("ok", false).put("error", error.toString().take(1024))
            }
            if (outcome != null) {
                runCatching {
                    client.componentRequest(
                        "component_result",
                        JSONObject().put("request_id", id).put("result", outcome)
                    )
                }
            }
        }
    }

    /** null means an asynchronous ActivityResult was launched and will complete later. */
    private suspend fun execute(
        requestId: String,
        kind: String,
        payload: JSONObject,
        deadlineElapsedMs: Long
    ): JSONObject? {
        return when (kind) {
        ResidentComponentProxyBroker.KIND_ACTIVITY_PRESENCE -> {
            val activity = ActivityLifecycleManager.getCurrentActivity()
            JSONObject().put("ok", true).put("present", activity != null)
                .put("activity_class", activity?.javaClass?.name ?: JSONObject.NULL)
        }
        ResidentComponentProxyBroker.KIND_WINDOW_LEASE -> {
            val window = ActivityLifecycleManager.getCurrentActivity()?.window
                ?: return JSONObject().put("ok", false).put("error", "NO_FOREGROUND_ACTIVITY")
            val leaseId = UUID.randomUUID().toString()
            windowLeases[leaseId] = WeakReference(window)
            JSONObject().put("ok", true).put("window_lease_id", leaseId)
        }
        ResidentComponentProxyBroker.KIND_WINDOW_FLAGS -> {
            val leaseId = payload.optString("window_lease_id").trim()
            val currentWindow = ActivityLifecycleManager.getCurrentActivity()?.window
                ?: return JSONObject().put("ok", false).put("error", "NO_FOREGROUND_ACTIVITY")
            val window = if (leaseId.isNotBlank()) {
                val leased = windowLeases[leaseId]?.get()
                    ?: return JSONObject().put("ok", false).put("error", "WINDOW_LEASE_UNAVAILABLE")
                if (leased !== currentWindow) {
                    windowLeases.remove(leaseId)
                    return JSONObject().put("ok", false).put("error", "WINDOW_LEASE_STALE")
                }
                leased
            } else {
                currentWindow
            }
            val addFlags = payload.optInt("add_flags", 0)
            val clearFlags = payload.optInt("clear_flags", 0)
            runOnUiThread {
                if (addFlags != 0) window.addFlags(addFlags)
                if (clearFlags != 0) window.clearFlags(clearFlags)
            }
            JSONObject().put("ok", true).put("window_lease_id", leaseId.ifBlank { JSONObject.NULL })
        }
        ResidentComponentProxyBroker.KIND_START_ACTIVITY -> {
            val activity = ActivityLifecycleManager.getCurrentActivity()
                ?: return JSONObject().put("ok", false).put("error", "NO_FOREGROUND_ACTIVITY")
            val intent = buildNeutralIntent(payload)
            runOnUiThread { activity.startActivity(intent) }
            JSONObject().put("ok", true)
        }
        ResidentComponentProxyBroker.KIND_SEND_BROADCAST -> {
            appContext.sendBroadcast(buildNeutralIntent(payload))
            JSONObject().put("ok", true)
        }
        ResidentComponentProxyBroker.KIND_START_SERVICE -> {
            val intent = buildNeutralIntent(payload)
            require(intent.component != null) { "Service component must be explicit" }
            val started = appContext.startService(intent)
            JSONObject().put("ok", true).put("component", started?.flattenToString() ?: JSONObject.NULL)
        }
        ResidentComponentProxyBroker.KIND_PERMISSION_REQUEST ->
            launchPermissionRequest(requestId, payload)
        ResidentComponentProxyBroker.KIND_ACTIVITY_RESULT ->
            launchActivityResult(requestId, payload, deadlineElapsedMs)
        ResidentComponentProxyBroker.KIND_HOST_PRIMITIVE -> {
            val primitiveId = payload.getString("primitive_id").trim().lowercase()
            check(CapabilityRegistry.isOwnedBy(primitiveId, CapabilityExecutionOwner.HOST)) {
                "Primitive is not Host-owned: $primitiveId"
            }
            val result = hostPrimitiveAdapter.invoke(
                ownerPluginId = payload.getString("owner_plugin_id").trim(),
                primitiveId = primitiveId,
                operation = payload.getString("operation").trim().lowercase(),
                parameters = payload.optJSONObject("parameters") ?: JSONObject()
            )
            JSONObject().put("ok", true).put("result", result)
        }
        ResidentComponentProxyBroker.KIND_HOST_AFFINITY_OPERATION -> {
            val ownerPluginId = payload.getString("owner_plugin_id").trim()
            val primitiveId = payload.getString("primitive_id").trim().lowercase()
            val operation = payload.getString("operation").trim().lowercase()
            val toolName = payload.getString("tool_name").trim()
            check(ownerPluginId.isNotEmpty()) { "Host-affinity operation requires owner_plugin_id" }
            CapabilityRegistry.requireDescriptor(primitiveId)
            check(HostPrimitiveGatewayBindings.affinityEnforced(primitiveId)) {
                "Primitive affinity routing is not enabled: $primitiveId"
            }
            check(HostPrimitiveGatewayBindings.requiresAndroidHost(primitiveId, operation)) {
                "Operation is not Android-Host affinity: $primitiveId/$operation"
            }
            val binding = HostPrimitiveGatewayBindings.operations(primitiveId)[operation]
                ?: error("Unknown Host Primitive operation: $primitiveId/$operation")
            check(binding.kind == HostGatewayRouteKind.HOST_TOOL) {
                "Test 9.1 Host-affinity transport only accepts HOST_TOOL bindings"
            }
            check(binding.target == toolName) {
                "Host-affinity target mismatch: $primitiveId/$operation -> $toolName"
            }

            val parameters =
                payload.optJSONArray("parameters")?.objects().orEmpty().map { item ->
                    ToolParameter(
                        name = item.getString("name"),
                        value = item.optString("value")
                    )
                }
            val tool = AITool(name = toolName, parameters = parameters)
            val results = JSONArray()
            when (binding.hostExecution) {
                HostGatewayHostExecution.MEDIA_PROJECTION_SCREEN_CAPTURE -> {
                    val (path, _) = hostScreenCaptureTools.captureScreenshot(tool)
                    val success = !path.isNullOrBlank()
                    results.put(
                        JSONObject()
                            .put("tool_name", toolName)
                            .put("success", success)
                            .put("result_data", StringResultData(path.orEmpty()).toJson())
                            .put(
                                "error",
                                if (success) JSONObject.NULL else "Screenshot failed"
                            )
                    )
                }
                null -> error(
                    "Host-affinity operation has no Host-local execution strategy: " +
                        "$primitiveId/$operation"
                )
            }
            JSONObject().put("ok", true).put("results", results)
        }
        ResidentComponentProxyBroker.KIND_UI_AUTOMATION_PRESENTATION -> {
            val action = payload.getString("action")
            when (action) {
                "tool_begin" -> {
                    val floating = FloatingChatService.getInstance()
                    floating?.setFloatingWindowVisible(false)
                    floating?.setStatusIndicatorVisible(
                        payload.optBoolean("show_status_indicator", true)
                    )
                }
                "tool_end" -> {
                    val floating = FloatingChatService.getInstance()
                    floating?.setFloatingWindowVisible(true)
                    floating?.setStatusIndicatorVisible(false)
                }
                else -> runOnUiThread {
                    val overlay = UIOperationOverlay.getInstance(appContext)
                    when (action) {
                        "overlay_tap" ->
                            overlay.showTap(
                                payload.getInt("x"),
                                payload.getInt("y"),
                                payload.optLong("auto_hide_delay_ms", 1500L)
                            )
                        "overlay_swipe" ->
                            overlay.showSwipe(
                                payload.getInt("start_x"),
                                payload.getInt("start_y"),
                                payload.getInt("end_x"),
                                payload.getInt("end_y"),
                                payload.optLong("auto_hide_delay_ms", 1500L)
                            )
                        "overlay_text" ->
                            overlay.showTextInput(
                                payload.getInt("x"),
                                payload.getInt("y"),
                                payload.optString("text"),
                                payload.optLong("auto_hide_delay_ms", 2000L)
                            )
                        "overlay_hide" -> overlay.hide()
                        "overlay_hide_immediate" -> overlay.hideImmediately()
                        else -> error("Unknown UI automation presentation action: $action")
                    }
                }
            }
            JSONObject().put("ok", true)
        }
        ResidentComponentProxyBroker.KIND_HOST_TOOL_EXECUTE -> {
            val toolName = payload.getString("tool_name").trim()
            check(toolName in HOST_EXECUTABLE_TOOLS) {
                "Host tool is not approved for Resident execution: $toolName"
            }
            val parameters =
                payload.optJSONArray("parameters")?.objects().orEmpty().map { item ->
                    ToolParameter(
                        name = item.getString("name"),
                        value = item.optString("value")
                    )
                }
            val tool = AITool(name = toolName, parameters = parameters)
            val (path, _) = hostScreenCaptureTools.captureScreenshot(tool)
            val success = !path.isNullOrBlank()
            JSONObject()
                .put("ok", true)
                .put("success", success)
                .put("path", path.orEmpty())
                .put("error", if (success) JSONObject.NULL else "Screenshot failed")
        }
        else -> JSONObject().put("ok", false).put("error", "UNKNOWN_COMPONENT_KIND")
        }
    }

    private fun launchPermissionRequest(
        requestId: String,
        payload: JSONObject
    ): JSONObject? {
        val parameters =
            payload.optJSONArray("parameters")?.objects().orEmpty().map { item ->
                ToolParameter(
                    name = item.optString("name"),
                    value = item.optString("value")
                )
            }
        val tool =
            AITool(
                name = payload.getString("tool_name"),
                parameters = parameters,
                description = payload.optString("tool_description")
            )
        val operationDescription = payload.optString("operation_description", tool.name)

        runOnUiThread {
            try {
                if (!permissionOverlay.hasOverlayPermission()) {
                    client.completeComponentAsync(
                        requestId,
                        JSONObject()
                            .put("ok", false)
                            .put("decision", "DENY")
                            .put("error", "OVERLAY_PERMISSION_REQUIRED")
                    )
                    return@runOnUiThread
                }

                permissionOverlay.show(tool, operationDescription) { decision ->
                    client.completeComponentAsync(
                        requestId,
                        JSONObject()
                            .put("ok", true)
                            .put("decision", decision.name)
                    )
                }
            } catch (error: Throwable) {
                com.ai.assistance.operit.util.AppLogger.e(
                    "ResidentHostComponentExecutor",
                    "Host permission presentation failed for ${tool.name}",
                    error
                )
                client.completeComponentAsync(
                    requestId,
                    JSONObject()
                        .put("ok", false)
                        .put("decision", "DENY")
                        .put("error", error.toString().take(1024))
                )
            }
        }
        return null
    }

    private fun launchActivityResult(
        requestId: String,
        payload: JSONObject,
        deadlineElapsedMs: Long
    ): JSONObject? {
        val activity = ActivityLifecycleManager.getCurrentActivity() as? ComponentActivity
            ?: return JSONObject().put("ok", false).put("error", "NO_COMPONENT_ACTIVITY")
        val intent = buildNeutralIntent(payload)
        runOnUiThread {
            val key = "resident-ui-proxy:$requestId"
            val launcherRef = AtomicReference<ActivityResultLauncher<Intent>?>(null)
            val launcher = activity.activityResultRegistry.register(
                key,
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val response = serializeActivityResult(result.resultCode, result.data)
                launcherRef.getAndSet(null)?.unregister()
                client.completeComponentAsync(requestId, response)
            }
            launcherRef.set(launcher)
            try {
                launcher.launch(intent)
                val remainingMs = (deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                activity.window.decorView.postDelayed({
                    launcherRef.getAndSet(null)?.unregister()
                }, remainingMs)
            } catch (error: Throwable) {
                launcherRef.getAndSet(null)?.unregister()
                throw error
            }
        }
        return null
    }

    private fun buildNeutralIntent(payload: JSONObject): Intent {
        val intent = Intent()
        payload.stringOrNull("action")?.trim()?.takeIf { it.isNotEmpty() }?.let { intent.action = it }
        val dataUri = payload.stringOrNull("data_uri")?.trim()?.takeIf { it.isNotEmpty() }
        val mimeType = payload.stringOrNull("mime_type")?.trim()?.takeIf { it.isNotEmpty() }
        when {
            dataUri != null && mimeType != null -> intent.setDataAndType(Uri.parse(dataUri), mimeType)
            dataUri != null -> intent.data = Uri.parse(dataUri)
            mimeType != null -> intent.type = mimeType
        }
        payload.stringOrNull("package_name")?.trim()?.takeIf { it.isNotEmpty() }?.let(intent::setPackage)
        val componentPackage = payload.stringOrNull("component_package")?.trim()?.takeIf { it.isNotEmpty() }
        val componentClass = payload.stringOrNull("component_class")?.trim()?.takeIf { it.isNotEmpty() }
        if (componentPackage != null || componentClass != null) {
            require(componentPackage != null && componentClass != null) {
                "component_package and component_class must be supplied together"
            }
            intent.component = if (componentClass.startsWith('.')) {
                ComponentName.createRelative(componentPackage, componentClass)
            } else {
                ComponentName(componentPackage, componentClass)
            }
        }
        payload.optJSONArray("categories")?.strings().orEmpty().forEach(intent::addCategory)
        if (payload.has("flags")) intent.flags = payload.optInt("flags", 0)
        payload.optJSONObject("extras")?.let { putNeutralExtras(intent, it) }
        require(intent.action != null || intent.data != null || intent.component != null) {
            "Neutral Intent requires action, data_uri or explicit component"
        }
        return intent
    }

    private fun putNeutralExtras(intent: Intent, extras: JSONObject) {
        val keys = extras.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = extras.get(key)) {
                JSONObject.NULL -> Unit
                is String -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is JSONArray -> {
                    val values = value.strings()
                    require(values.size == value.length()) { "Only string arrays are allowed in Intent extras: $key" }
                    intent.putStringArrayListExtra(key, ArrayList(values))
                }
                else -> error("Unsupported neutral Intent extra type for $key: ${value.javaClass.name}")
            }
        }
    }

    private fun serializeActivityResult(resultCode: Int, data: Intent?): JSONObject {
        val result = JSONObject().put("ok", true).put("result_code", resultCode)
        if (data == null) return result.put("data", JSONObject.NULL)
        val neutral = JSONObject()
            .put("action", data.action ?: JSONObject.NULL)
            .put("data_uri", data.dataString ?: JSONObject.NULL)
            .put("mime_type", data.type ?: JSONObject.NULL)
            .put("flags", data.flags)
            .put("categories", JSONArray(data.categories?.toList()?.sorted().orEmpty()))
        val clipUris = JSONArray()
        data.clipData?.let { clip ->
            for (index in 0 until minOf(clip.itemCount, 32)) {
                clip.getItemAt(index).uri?.toString()?.let(clipUris::put)
            }
        }
        neutral.put("clip_uris", clipUris)
        neutral.put("extras", safeResultExtras(data.extras))
        return result.put("data", neutral)
    }

    private fun safeResultExtras(bundle: android.os.Bundle?): JSONObject {
        val result = JSONObject()
        if (bundle == null) return result
        bundle.keySet().forEach { key ->
            when (val value = bundle.get(key)) {
                null -> result.put(key, JSONObject.NULL)
                is String, is Boolean, is Int, is Long, is Double, is Float -> result.put(key, value)
                is Array<*> -> if (value.all { it is String }) result.put(key, JSONArray(value.toList()))
                is List<*> -> if (value.all { it is String }) result.put(key, JSONArray(value))
                else -> Unit // Parcelable, Binder and arbitrary framework objects are intentionally dropped.
            }
        }
        return result
    }

    private fun runOnUiThread(block: () -> Unit) {
        // Permission overlays use the Host main Looper even when no Activity is foreground.
        // Activity-specific operations already validate their Activity/window above.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        check(mainHandler.post {
            try { block() } catch (error: Throwable) { failure.set(error) } finally { latch.countDown() }
        }) { "Host main Looper rejected component request" }
        check(latch.await(3, TimeUnit.SECONDS)) { "Host UI thread did not execute component request" }
        failure.get()?.let { throw it }
    }

    private companion object {
        val HOST_EXECUTABLE_TOOLS = setOf("capture_screenshot")
    }
}

private fun childSnapshot(item: JSONObject): ChildExtensionSnapshot = ChildExtensionSnapshot(
    extensionId = item.getString("extension_id"), version = item.getString("version"), displayName = item.getString("display_name"),
    description = item.stringOrNull("description"),
    target = ChildExtensionTarget(item.getString("parent_plugin_id"), item.getString("point"), item.getInt("api_version")),
    lifecycle = ChildExtensionLifecycle.valueOf(item.getString("lifecycle").uppercase()), enabled = item.getBoolean("enabled"),
    roles = item.optJSONArray("roles")?.strings()?.toSet().orEmpty(), useCount = item.optLong("use_count", 0L),
    lastError = item.stringOrNull("last_error")
)

private fun childBackup(item: JSONObject): ChildExtensionBackupSnapshot = ChildExtensionBackupSnapshot(
    extensionId = item.getString("extension_id"), version = item.getString("version"), displayName = item.getString("display_name"),
    description = item.stringOrNull("description"),
    target = ChildExtensionTarget(item.getString("parent_plugin_id"), item.getString("point"), item.getInt("api_version")),
    roles = item.optJSONArray("roles")?.strings()?.toSet().orEmpty(), packageSha256 = item.getString("package_sha256"),
    backedUpAtEpochMs = item.getLong("backed_up_at"), wasEnabled = item.getBoolean("was_enabled"),
    installed = item.getBoolean("installed"), installedVersion = item.stringOrNull("installed_version")
)

private fun JSONArray.objects(): List<JSONObject> = buildList { for (index in 0 until length()) add(getJSONObject(index)) }
private fun JSONArray.strings(): List<String> = buildList { for (index in 0 until length()) add(getString(index)) }
private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
private fun JSONObject.stringMap(): Map<String, String> = buildMap {
    val iterator = keys()
    while (iterator.hasNext()) { val key = iterator.next(); put(key, getString(key)) }
}
